(ns automation.watch-status
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
            [clj-time.core]))

(defn
  ^{:command {:name "button-commit"
              :description "make a commit button"
              :intent ["kick me"]
              :secrets [{:uri "github://user_token?scopes=repo"}]}}
  kick-commit
  [o]
  (api/actionable-message
   o
   {:text "okay, go"
    :attachments
    [{:footer ""
      :callback_id "callbackid"
      :text "kick commit?"
      :markdwn_in ["text"]
      :actions [{:text "do it"
                 :type "button"
                 :atomist/command {:command "commit"
                                   :parameters [{:name "message" :value "kick the thing"}]}
                 :value "do it ... do it"}]}]
    :unfurl_links false
    :unfurl_media false}))

(defn
  ^{:event {:name "onK8Pod"
            :description "watch K8Pods"
            :secrets [{:uri "github://org_token"}]
            :subscription (slurp (io/resource "onK8Pod.graphql"))}}
  image-linked
  [event]
  (log/infof "got event %s" event))