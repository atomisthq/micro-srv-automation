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
            [tentacles.repos :as repos])
  (:import (java.io File)))

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
         :token (api/get-secret-value event "github://org_token")}} (fn [dir] (assoc event :dir dir))))))

(defn build-docker [event]
  (log/infof "do docker in cloned workspace %s" (:dir event)))

(defn
  ^{:event {:name "onPush"
            :description "watch Pushes"
            :subscription (slurp (io/resource "on-push.graphql"))}}
  on-push
  [event]
  ((-> build-docker
       (with-cloned-workspace)
       (with-build-events)
       (with-error-handler)) event))

(defn
  ^{:event {:name "onBuild"
            :description "watch Builds"
            :secrets ["github://org_token"]
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
  ^{:command {:name "commit"
              :description "make a commit"
              :intent ["kick commit"]
              :mapped_parameters [{:local_key "repository" :foreign_key "atomist://github/repository" :required true}
                                  {:local_key "owner" :foreign_key "atomist://github/repository/owner" :required true}
                                  {:local_key "user" :foreign_key "atomist://github/username" :required true}]
              :secrets ["github://user_token?scopes=repo"]
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

