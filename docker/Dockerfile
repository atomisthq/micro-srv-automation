FROM clojure

MAINTAINER Jim Clark <jim@atomist.com>

RUN wget -q -O /usr/bin/lein \
    https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein \
    && chmod +x /usr/bin/lein

RUN lein

RUN mkdir -p /usr/src/app \
    && mkdir -p /usr/src/app/bin \
    && mkdir -p /usr/src/app/lib

WORKDIR /usr/src/app

COPY target/lib /usr/src/app/lib
COPY resources/config.edn /usr/src/app/
COPY target/micro-srv-automation.jar /usr/src/app/

CMD ["java", "-Xmx1024m", "-Dconfig.edn=/usr/src/app/config.edn", "-jar", "/usr/src/app/micro-srv-automation.jar"]
