(ns automation.build
  (:require [mount.core :as mount]
            [com.atomist.automation.core]
            [clojure.tools.logging :as log]
            [com.atomist.automation.core :as api]
            [clojure.java.io :as io]
            [automation.git :as git]
            [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.test :refer :all]
            [tentacles.core :as tentacles]
            [tentacles.repos :as repos]
            [tentacles.data :as data]
            [automation.lein-runner :as lein]
            [clj-time.core]
            [clojure.java.shell :as sh]
            [clojure.string :as string]
            [semver.core :as s]
            [com.atomist.automation.config-service :as cs])
  (:import (java.io File BufferedReader StringReader))
  (:gen-class))

(defn- get-org-secret-or-use-start-token
  [o]
  (or (api/get-secret-value o "github://org_token")
      (System/getenv "ATOMIST_TOKEN")
      (cs/get-config-value [:github-token])))

(defn- tweak-repo
  [o message dir]
  (with-open [f (io/writer (File. dir "kick.txt") :append true)]
    (.write f message))
  (api/simple-message o (format "tweaked %s with message %s" "dir/kick.txt" message))
  true)

(defn- get-master-ref
  [owner repo user]
  {:commit
   {:owner owner
    :repo repo
    :name user
    :email "ding.dong.the.wonder.elf@gmail.com"}})

(defn generic-build-event [team-id commit-event status]
  (log/info
   (client/post (format "https://webhook.atomist.com/atomist/build/teams/%s" team-id)
                {:body (json/json-str {:repository {:owner_name (-> commit-event :repo :org :owner)
                                                    :name (-> commit-event :repo :name)}
                                       :type "push"
                                       :status status
                                       :commit (-> commit-event :sha)
                                       :branch (or (-> commit-event :branch) "master")})
                 :content-type :json})))

(defn link-image [event image team-id commit]
  (log/info "link-image")
  (log/info
   (client/post (format "https://webhook.atomist.com/atomist/link-image/teams/%s" team-id)
                {:body (json/json-str {:git {:owner (-> commit :repo :org :owner)
                                             :repo (-> commit :repo :name)
                                             :sha (-> commit :sha)}
                                       :docker {:image image}
                                       :type "link-image"})
                 :content-type :json})))

(defn make-tag [event version team-id commit]
  (log/info "make tag")
  (log/info
   (tentacles/with-defaults
     {:oauth-token (get-org-secret-or-use-start-token event)}
     (tentacles.data/create-tag
      (-> commit :repo :org :owner)
      (-> commit :repo :name)
      version
      "created by atomist service automation"
      (-> commit :sha)                                       ;; object reference
      "commit"                                               ;; commit, tree, or blob
      {:name "Atomist bot"
       :email "bot@atomist.com"
       :data (str (clj-time.core/now))}))))

(defn with-build-events [f]
  (fn [event]
    (let [team-id (api/get-team-id event)
          commit (-> event :data :Push first :after)]
      (generic-build-event team-id commit "started")
      (try
        (let [response (f event)]
          (generic-build-event team-id commit "passed")
          response)
        (catch Throwable t
          (log/error t)
          (generic-build-event team-id commit "failed"))))))

(defn with-error-handler [f]
  (fn [event]
    (try
      (f event)
      (catch Throwable t
        (log/error t)))))

(def atomist-commit-message "atomist:release")

(defn with-skip-bot-commit [f]
  (fn [event]
    (let [commit-message (-> event :data :Push first :after :message)]
      (log/infof "check after commit message %s" commit-message)
      (if (.contains commit-message atomist-commit-message)
        (do
          (log/infof "skipping this atomist:commit push"))
        (f event)))))

(defn with-cloned-workspace [f]
  (fn [event]
    (let [commit (-> event :data :Push first :after)
          git-errors
          (git/edit-in-a-cloned-workspace
           {:commit
            {:owner (-> commit :repo :org :owner)
             :repo (-> commit :repo :name)
             :sha "master"
             :name "Atomist bot"
             :email "bot@atomist.com"
             :token (get-org-secret-or-use-start-token event)}}
           (fn [dir] (f (assoc event :dir dir)))
           atomist-commit-message)]
      (log/info "git-errors " git-errors)
      (if (not (empty? (:errors git-errors)))
        (throw (ex-info "errors processing" (:errors git-errors)))
        event))))

(defn- call-build
  [{:keys [dir version] :as event}]
  (let [env {"DOCKER_REGISTRY" (System/getenv "DOCKER_REGISTRY")
             "DOCKER_USER" (System/getenv "DOCKER_USER")
             "DOCKER_PASSWORD" (System/getenv "DOCKER_PASSWORD")}]
    (log/info "environment " (System/getenv))
    (log/info "call ls in dir" (sh/with-sh-dir dir (sh/sh "ls" "-l")))
    (log/info "call chmod" (sh/with-sh-dir dir (sh/sh "chmod" "755" "build.sh")))
    (log/infof "call build.sh with environment %s and version %s" (dissoc env "DOCKER_PASSWORD") version)
    (let [{:keys [exit out err] :as d}
          (sh/with-sh-dir dir (sh/with-sh-env env (sh/sh "./build.sh" version)))]
      (log/infof "\nexit code:  %s\nout:  %s\nerr:  %s\n" exit out err)
      (if (= 0 exit)
        (do
          (log/info "docker login " (sh/with-sh-dir dir (sh/sh "sh" "-c" (format "docker login %s -u %s -p %s" (System/getenv "DOCKER_REGISTRY") (System/getenv "DOCKER_USER") (System/getenv "DOCKER_PASSWORD")))))
          (let [new-image (string/trim-newline (slurp (File. dir "image.txt")))]
            (log/info "docker push " new-image " -> " (sh/with-sh-dir dir (sh/sh "docker" "push" new-image)))
            (assoc event :version version :image new-image)))
        (throw (ex-info "docker build failed" d))))))

(defn- increment-patch [s]
  (if (s/valid? s)
    (let [new (-> s
                  (s/parse)
                  (s/increment-patch)
                  (s/render))]
      (log/infof "%s -> %s" s new) new)
    (do
      (log/warnf "unable to increment %s - not valid semantic version" s) s)))

(defn- update-version
  [event]
  (let [version-file (File. (:dir event) "version.txt")
        updated (-> (slurp version-file)
                    (string/trim)
                    (increment-patch))]
    (spit version-file updated)
    (assoc event :version updated)))

(defn with-docker-build [f]
  (fn [{:keys [dir] :as event}]
    (cond

      ;; use a build.sh file if one is present
      (and
       (.exists (File. (:dir event) "build.sh"))
       (.exists (File. (:dir event) "version.txt")))
      (do
        (log/info "we have discovered a build.sh")
        (-> event
            (update-version)
            (call-build)
            (f)))

      ;; if this seems to be a leiningen project
      (.exists (File. (:dir event) "project.clj"))
      (do
        (log/infof "do docker in cloned workspace %s" (:dir event))
        (lein/lein-run event lein/log-run lein/set-release-version)
        (lein/lein-run event lein/log-run lein/docker-build)
        (let [[version image] (lein/project-details event)]
          (f (assoc event
                    :image image
                    :version version)))
        (lein/lein-run event lein/log-run lein/reset-release))

      :else
      (log/info "there is nothing to build here"))))

(defn make-tag-and-link-image
  [event]
  (let [team-id (api/get-team-id event)
        commit (-> event :data :Push first :after)
        version (:version event)
        image (:image event)]
    (make-tag event version team-id commit)
    (link-image event image team-id commit)))

(defn
  ^{:event {:name "onPush"
            :description "watch for Pushes and see whether we can create Docker Container"
            :secrets [{:uri "github://org_token"}]
            :subscription (slurp (io/resource "on-push.graphql"))}}
  on-push
  [event]
  (clojure.core.async/thread
    ((-> make-tag-and-link-image
         (with-docker-build)
         (with-cloned-workspace)
         (with-build-events)
         (with-skip-bot-commit)
         (with-error-handler)) event)))

(defn
  ^{:event {:name "onBuild"
            :description "watch for passed Builds and set a Pending Status"
            :secrets [{:uri "github://org_token"}]
            :subscription (slurp (io/resource "on-build.graphql"))}}
  on-build
  [event]
  (when (= "passed" (-> event :data :Build first :status))
    (log/info "CREATE PENDING STATUS")
    (try
      (->
       (tentacles/with-defaults
         {:oauth-token (get-org-secret-or-use-start-token event)}
         (let [commit (-> event :data :Build first :commit)]
           (repos/create-status
            (-> commit :repo :org :owner)
            (-> commit :repo :name)
            (-> commit :sha)
            {:state "pending" :context "deploy/atomist/k8s/production"}))))
      (catch Throwable t
        (log/error t)))))

(defn
  ^{:event {:name "KubeDeploySub"
            :description "watch Status"
            :secrets [{:uri "github://org_token"}]
            :subscription (slurp (io/resource "on-status.graphql"))}}
  kube-deploy
  [event]
  (log/infof "DEPLOY PENDING got event %s" event))

(defn
  ^{:event {:name "OnImageLinked"
            :description "watch Status"
            :secrets [{:uri "github://org_token"}]
            :subscription (slurp (io/resource "on-image-link.graphql"))}}
  image-linked
  [event]
  (log/infof "IMAGE LINKED got event %s" event))

(defn
  ^{:event {:name "KubeStatusSub"
            :description "watch for k8-automation successful deployments"
            :secrets [{:uri "github://org_token"}]
            :subscription (slurp (io/resource "on-status-deployed.graphql"))}}
  all-status
  [event]
  (when (let [context (-> event :data :Status first :context)
              state (-> event :data :Status first :state)]
          (and
           (= "deploy/atomist/k8s/production" context)
           (= "success" state)))
    (log/info "DEPLOY SUCCESS %s" event)
    (let [s (-> event :data :Status first :targetUrl)
          channel (-> event :data :Status first :commit :repo :channels first)
          team (:team channel)]
      (log/info (format "kube deployment for %s complete %s"
                        team
                        (clojure.string/replace s #"sdm.atomist.io" "192.168.99.100")))
      (-> event
          (api/add-slack-source (:id team) (:name team))
          (api/channel (:name channel))
          (api/actionable-message
           {:text (format "kube deployment complete %s" (clojure.string/replace s #"sdm.atomist.io" "192.168.99.100"))
            :attachments
            [{:footer ""
              :callback_id "callbackid"
              :text "release the canary"
              :markdwn_in ["text"]
              :actions [{:text "let it fly"
                         :type "button"
                         :atomist/command {:command "releaseCanary"
                                           :parameters []}
                         :value "do it ... do it"}]}]
            :unfurl_links false
            :unfurl_media false})))))

(defn
  ^{:command {:name "commit"
              :description "make a commit"
              :intent ["kick commit"]
              :mapped_parameters [{:name "repository" :uri "atomist://github/repository"}
                                  {:name "owner" :uri "atomist://github/repository/owner"}
                                  {:name "user" :uri "atomist://github/username"}]
              :secrets [{:uri "github://user_token?scopes=repo"}]
              :parameters [{:name "message" :pattern ".*" :required false}]}}
  commit
  [o]
  (let [message (or (api/get-parameter-value o "message") "kick")]
    (git/edit-in-a-cloned-workspace
     (->
      (get-master-ref
       (api/mapped-parameter-value o "owner")
       (api/mapped-parameter-value o "repository")
       (api/mapped-parameter-value o "user"))
      (assoc-in [:commit :token] (api/get-secret-value o "github://user_token?scopes=repo")))
     (partial tweak-repo o message)
     message)))

(defn -main [& args]
  (log/info (mount/start))
  (.join (Thread/currentThread)))

