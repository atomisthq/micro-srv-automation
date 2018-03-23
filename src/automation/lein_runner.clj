(ns automation.lein-runner
  (:require [clojure.tools.logging :as log]))

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
