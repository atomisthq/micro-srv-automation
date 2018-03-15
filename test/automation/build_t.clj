(ns automation.build-t
  (:require [clojure.test :refer :all]
            [automation.build :refer :all]
            [clojure.tools.logging :as log]
            [com.atomist.automation.core :as api]
            [automation.git :as git]))

(deftest run-middleware-tests
  (with-redefs [automation.build/generic-build-event (fn [& args] (log/info "generic-build-event " args))
                api/get-team-id (constantly "team-id")
                git/delete-recursively (constantly true)
                com.atomist.git.core/perform (fn [dir & args] (log/info "perform " dir args))
                api/get-secret-value (constantly "token")]
    ((-> build-docker
         (with-cloned-workspace)
         (with-build-events)
         (with-error-handler)) {:data {:Push [{:after {:repo {:org {:owner "owner"}}
                                                       :name "repo"}}]}})))