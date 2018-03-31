(ns automation.git
  (:require [com.atomist.git.core :as git]
            [clojure.tools.logging :as log]
            [tentacles.core]
            [tentacles.pulls])
  (:import (java.io File)
           (java.util UUID)
           (clojure.lang ExceptionInfo)))

(defn delete-recursively [fname]
  (doseq [f (reverse (file-seq (clojure.java.io/file fname)))]
    (clojure.java.io/delete-file f)))

(defn get-tmp-dir []
  (File. "./whatever"))

(defn run-with-cloned-workspace
  "setup and teardown the cloned repo
    return the result of running f
    on the cloned dir"
  [{{:keys [owner repo sha token]} :commit} f]
  (let [dir (get-tmp-dir)]
    (try
      (log/infof "clone %s and checkout %s" repo sha)
      (git/perform dir
                   :git-clone {:org owner :repo-name repo :branch "master" :oauth-token token}
                   :git-checkout (if sha {:branch sha} {:branch "master"}))
      (log/infof "%s is cloned and %s is checked out in %s" repo sha (.getPath dir))

      (f dir)
      (catch Throwable t
        (log/error t (str "error running operation on " repo " " (.getMessage t)))
        {:errors [{:could_not_clone true}]})
      (finally
        (delete-recursively dir)))))

(defn edit-in-a-cloned-workspace
  [{{:keys [owner repo sha token name email]} :commit} f message]
  (let [dir (get-tmp-dir)]
    (try
      (log/infof "edit %s and checkout %s" repo sha)
      (git/perform dir
                   :git-clone {:org owner :repo-name repo :branch "master" :oauth-token token}
                   :git-checkout (if sha {:branch sha} {:branch "master"}))
      (log/infof "%s is cloned and %s is checked out in %s" repo sha (.getPath dir))

      (f dir)

      (git/perform dir
                   :git-add {:file-pattern "."}
                   :git-commit {:message message :name name :email email}
                   :git-push {:branch "master" :oauth-token token :remote "origin"})
      (catch ExceptionInfo ei
        (log/error ei (str "error processing repo " repo " " ei))
        {:errors [{:processing (ex-data ei)}]})
      (catch Throwable t
        (log/error t (str "error running operation on " repo " " (.getMessage t)))
        {:errors [{:could_not_clone true}]})
      (finally
        (delete-recursively dir)))))

(defn raise-PR-in-a-cloned-workspace
  [{{:keys [owner repo sha token name email]} :commit} f title message]
  (let [dir (get-tmp-dir)]
    (try
      (let [branch (format "atomist-%s" (str (UUID/randomUUID)))]

        (log/infof "edit %s and checkout %s" repo branch)

        (git/perform dir
                     :git-clone {:org owner :repo-name repo :branch "master" :oauth-token token}
                     :git-checkout {:branch "master"}
                     :git-branch-create {:branch branch}
                     :git-checkout {:branch branch})
        (log/infof "%s is cloned and %s is checked out in %s" repo branch (.getPath dir))

        (if (f dir)

          (do (git/perform dir
                           :git-add {:file-pattern "."}
                           :git-commit {:message message :name name :email email}
                           :git-push {:branch branch :oauth-token token :remote "origin"})
              (tentacles.core/with-defaults
                {:oauth-token token}
                (tentacles.pulls/create-pull owner repo title "master" branch {:body message})))
          (log/info "Not raising PR as no changes made")))
      (catch Throwable t
        (log/error t (str "error running operation on " repo " " (.getMessage t)))
        {:errors [{:could_not_clone true}]})
      (finally
        (delete-recursively dir)))))