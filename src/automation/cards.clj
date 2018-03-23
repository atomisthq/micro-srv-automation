(ns automation.cards
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
            [com.atomist.automation.config-service :as cs]))

(defn
  ^{:command {:name "releaseCanary"
              :description "release the Canary"
              :mapped_parameters [{:name "repository" :uri "atomist://github/repository"}
                                  {:name "owner" :uri "atomist://github/repository/owner"}
                                  {:name "user" :uri "atomist://github/username"}]
              :secrets [{:uri "github://user_token?scopes=repo"}]}}
  release-the-canary
  [o]
  (api/simple-message o "release the canary"))

(ns-unmap *ns* 'on-card)

#_(defn
    ^{:event {:name "OnCards"
              :description "watch Cards"
              :secrets [{:uri "github://org_token"}]
              :subscription (slurp (io/resource "on-card.graphql"))}}
    on-card
    [event]
    (log/infof "CARD %s" event)
    (let [provenance "@atomist/micro-srv-automation#OnCards"
          card (-> event :data :Card first)]
      (if (not (some #(= provenance (:name %)) (:provenance card)))
        (-> event
            (assoc :automation {:name "releaseCanary"
                                :version (cs/get-config-value [:version])})
            (api/ingest (-> card
                            (update-in [:actions] conj {:registration (cs/get-config-value [:name])
                                                        :command "releaseCanary"
                                                        :text "Canary"
                                                        :role ""
                                                        :type "button"})
                            (update-in [:provenance] (fnil conj []) {:name provenance}))
                        "Card")))))
