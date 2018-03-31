(ns automation.lein-runner
  (:require [clojure.tools.logging :as log])
  (:import (java.io StringReader BufferedReader)))

(def set-release-version [["clean"]
                          ["change" "version" "leiningen.release/bump-version" "release"]])

(def docker-build [["metajar"]
                   ["container" "build"]
                   ["container" "push"]])

(def reset-release [["change" "version" "leiningen.release/bump-version" ":patch"]])

(def extract-project-details [["pprint" ":name" ":version" ":container"]])

(defn log-run [d] (log/info (format "status: %s" (:exit d))) d)

(defn- run [dir f & args]
  (log/info "running " args)
  (let [{:keys [out exit err] :as d}
        (clojure.java.shell/with-sh-dir
          dir
          (apply clojure.java.shell/sh args))]

    (if (not (= 0 exit))
      (throw (ex-info "failed run" d))
      (f d))))

(defn- lein-do-args [commands]
  (clojure.string/split
   (str "lein do " (apply str (interpose ", " (map #(clojure.string/join " " %) commands))))
   #" "))

(defn lein-run [event logger commands]
  (->> (lein-do-args commands)
       (apply run (:dir event) logger)))

(defn project-details [event]
  (let [lines (->> (lein-run event log-run extract-project-details)
                   :out
                   (StringReader.)
                   (BufferedReader.)
                   (line-seq))
        project-name (-> lines first (read-string))
        project-version (-> lines second (read-string))
        hub (->> (drop 2 lines) (apply str) (read-string) :hub)]
    [project-version (format "%s/%s:%s" hub project-name project-version)]))
