(defproject micro-srv-automation "0.1.1-SNAPSHOT"
  :description ""
  :url "http://example.com/FIXME"
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/core.async "0.4.474"]         ; because someones core.async clashes with 1.9.0
                 [com.atomist/automation-client-clj "0.3.15" :exclusions [org.clojure/clojure commons-logging log4j org.slf4j/slf4j-log4j12]]

                 ;; logging
                 [io.clj/logging "0.8.1" :exclusions [org.clojure/tools.logging]]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [org.slf4j/jcl-over-slf4j "1.7.25"]
                 [org.slf4j/jul-to-slf4j "1.7.25"]
                 [org.slf4j/log4j-over-slf4j "1.7.25"]
                 [org.slf4j/slf4j-api "1.7.25"]
                 [io.logz.logback/logzio-logback-appender "1.0.17"]

                 ;; util
                 [clj-time "0.14.2" :exclusions [org.slf4j/slf4j-log4j12 commons-logging]]

                 ;; github
                 [tentacles "0.5.1"]
                 [com.atomist/clj-git-lib "0.3.0" :exclusions [com.fasterxml.jackson.dataformat/jackson-dataformat-cbor org.clojure/clojure commons-logging org.slf4j/slf4j-log4j12 prismatic/schema]]

                 [com.climate/java.shell2 "0.1.0"]]

  :min-lein-version "2.7.0"

  :container {:name "micro-srv-automation"
              :dockerfile "/docker"
              :hub "sforzando-dockerv2-local.jfrog.io"}

  :jar-name "micro-srv-automation.jar"
  :main automation.build

  :resource-paths ["resources"]

  :plugins [[lein-metajar "0.1.1"]
            [clj-plugin "0.1.16" :exclusions [org.clojure/clojure]]
            [lein-environ "1.1.0"]]

  :profiles {:metajar {:direct-linking true
                       :aot :all}
             :dev {:dependencies [[clj-local-secrets "0.5.1" :exclusions [org.clojure/clojure]]]
                   :source-paths ["dev/clj"]
                   :repl-options {:init-ns user}}}

  :repositories [["releases" {:url "https://sforzando.artifactoryonline.com/sforzando/libs-release-local"
                              :username [:gpg :env/artifactory_user]
                              :password [:gpg :env/artifactory_pwd]}]
                 ["plugins" {:url "https://sforzando.artifactoryonline.com/sforzando/plugins-release"
                             :username [:gpg :env/artifactory_user]
                             :password [:gpg :env/artifactory_pwd]}]])
