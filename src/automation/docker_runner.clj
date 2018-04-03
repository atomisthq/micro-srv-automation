(ns automation.docker-runner
  (:require [clojure.tools.logging :as log]
            [clojure.java.shell :as sh]
            [clojure.string :as string])
  (:import (java.io File)))

(defn read-image [dir]
  (string/trim-newline (slurp (File. dir "image.txt"))))

(defn login-and-push
  "login to docker and push"
  [dir image]
  (log/info "docker login "
            (sh/with-sh-dir
              dir
              (sh/sh "sh" "-c" (format "docker login %s -u %s -p %s" (System/getenv "DOCKER_REGISTRY") (System/getenv "DOCKER_USER") (System/getenv "DOCKER_PASSWORD")))))
  (log/info "docker push " image " -> " (sh/with-sh-dir dir (sh/sh "docker" "push" image))))

(defn call-build
  [{:keys [dir repo version] :as event}]
  (let [env {"DOCKER_REGISTRY" (System/getenv "DOCKER_REGISTRY")
             "DOCKER_USER" (System/getenv "DOCKER_USER")
             "DOCKER_PASSWORD" (System/getenv "DOCKER_PASSWORD")}]
    (log/info "environment " (System/getenv))
    (log/info "call ls in dir" (sh/with-sh-dir dir (sh/sh "ls" "-l")))
    (-> event
        (assoc :image
               (cond
                 (.exists (File. dir "build.sh"))
                 (do
                   (log/info "call chmod" (sh/with-sh-dir dir (sh/sh "chmod" "755" "build.sh")))
                   (log/infof "call build.sh with environment %s and version %s" (dissoc env "DOCKER_PASSWORD") version)
                   (let [{:keys [exit out err] :as d}
                         (sh/with-sh-dir dir (sh/with-sh-env env (sh/sh "./build.sh" version)))]
                     (log/infof "\nexit code:  %s\nout:  %s\nerr:  %s\n" exit out err)
                     (if (= 0 exit)
                       (let [new-image (read-image dir)]
                         (login-and-push dir new-image)
                         new-image)
                       (throw (ex-info "build.sh failed" d)))))

                 (.exists (File. dir "docker/Dockerfile"))
                 (let [image (format "%s/%s:%s" (System/getenv "DOCKER_REGISTRY") repo version)
                       sh-args ["docker" "build" "-t" image "-f" "docker/Dockerfile" "."]
                       {:keys [exit out err] :as d} (sh/with-sh-dir dir (apply sh/sh sh-args))]
                   (log/info "call docker build:  %s" (apply str (interpose " " sh-args)))
                   (log/info d)
                   (if (= 0 exit)
                     (login-and-push dir image)
                     (throw (ex-info "failure to call docker build " d)))
                   image)

                 :else
                 (throw (ex-info "unable to locate docker/Dockerfile in repo" event)))))))

