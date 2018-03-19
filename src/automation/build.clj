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
            [clj-time.core])
  (:import (java.io File BufferedReader StringReader)))

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
                                       :branch (or (-> commit-event :branch) "master") ;; TODO
})
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
     {:oauth-token (api/get-secret-value event "github://org_token")}
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

(defn with-cloned-workspace [f]
  (fn [event]
    (let [commit (-> event :data :Push first :after)]
      (git/run-with-cloned-workspace
       {:commit
        {:owner (-> commit :repo :org :owner)
         :repo (-> commit :repo :name)
         :sha "master"
         :token (api/get-secret-value event "github://org_token")}}
       (fn [dir] (f (assoc event :dir dir)))))))

(defn with-docker-build [f]
  (fn [event]
    (log/infof "do docker in cloned workspace %s" (:dir event))
    (f (assoc event
              :image ""
              :version ""))))

(->
 (clojure.java.shell/with-sh-dir
   (java.io.File. "/Users/slim/repo/minikube-test")
   (clojure.java.shell/sh "lein" "pprint" ":name" ":version" ":container"))
 :out
 (StringReader.)
 (BufferedReader.)
 (line-seq))
;; hub/name:version

(defn make-tag-and-link-image [event]
  (let [team-id (api/get-team-id event)
        commit (-> event :data :Push first :after)
        version (:version event)
        image (:image event)]
    (make-tag event version team-id commit)
    (link-image event image team-id commit)))

(defn
  ^{:event {:name "onPush"
            :description "watch Pushes"
            :secrets [{:uri "github://org_token"}]
            :subscription (slurp (io/resource "on-push.graphql"))}}
  on-push
  [event]
  ((-> make-tag-and-link-image
       (with-docker-build)
       (with-cloned-workspace)
       (with-build-events)
       (with-error-handler)) event))

(defn
  ^{:event {:name "onBuild"
            :description "watch Builds"
            :secrets [{:uri "github://org_token"}]
            :subscription (slurp (io/resource "on-build.graphql"))}}
  on-build
  [event]
  (when (= "passed" (-> event :data :Build first :status))
    (try
      (->
       (tentacles/with-defaults
         {:oauth-token (api/get-secret-value event "github://org_token")}
         (let [commit (-> event :data :Build first :commit)]
           (repos/create-status
            (-> commit :repo :org :owner)
            (-> commit :repo :name)
            (-> commit :sha)
            {:state "pending" :context "deploy/atomist/k8s/production"})))
       clojure.pprint/pprint
       with-out-str
       log/info)
      (catch Throwable t
        (log/error t)))))

(defn
  ^{:event {:name "KubeDeploySub"
            :description "watch Status"
            :secrets [{:uri "github://org_token"}]
            :subscription (slurp (io/resource "on-status.graphql"))}}
  kube-deploy
  [event]
  (log/infof "got event %s" event))

(defn
  ^{:event {:name "KubeDeploySub"
            :description "watch Status"
            :secrets [{:uri "github://org_token"}]
            :subscription (slurp (io/resource "on-image-link.graphql"))}}
  image-linked
  [event]
  (log/infof "got event %s" event))

(defn
  ^{:event {:name "onK8Pod"
            :description "watch K8Pods"
            :secrets [{:uri "github://org_token"}]
            :subscription (slurp (io/resource "onK8Pod.graphql"))}}
  image-linked
  [event]
  (log/infof "got event %s" event))

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

(defn
  ^{:command {:name "button-commit"
              :description "make a commit button"
              :intent ["kick me"]
              :secrets [{:uri "github://user_token?scopes=repo"}]}}
  kick-commit
  [o]
  (api/actionable-message
   o
   {:text         "okay, go"
    :attachments
    [{:footer      ""
      :callback_id "callbackid"
      :text        "kick commit?"
      :markdwn_in  ["text"]
      :actions     [{:text            "do it"
                     :type            "button"
                     :atomist/command {:command "commit"
                                       :parameters [{:name "message" :value "kick the thing"}]}
                     :value           "do it ... do it"}]}]
    :unfurl_links false
    :unfurl_media false}))

(defn -main [& args]
  (log/info (mount/start))
  (.join (Thread/currentThread)))

