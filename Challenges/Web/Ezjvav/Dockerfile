ARG BASE_IMAGE=eclipse-temurin:8-jdk-noble

FROM ${BASE_IMAGE} AS helper-build

WORKDIR /build/helper

COPY helper/ /build/helper/

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends cargo rustc build-essential ca-certificates; \
    cargo build --release; \
    rm -rf /var/lib/apt/lists/*

FROM ${BASE_IMAGE}

ENV CATALINA_HOME=/opt/tomcat
ENV GHOST_HOME=/opt/ghost

WORKDIR /build

COPY src/ /build/source/src/
COPY samples/ /build/source/samples/
COPY catalog-main.jar /build/catalog-main.jar
COPY helper/.build-config.env /build/helper-runtime.env
COPY entrypoint.sh /entrypoint.sh
COPY --from=helper-build /build/helper/target/release/ghost-helper /build/studio-agent.bin

RUN set -eux; \
    mkdir -p /opt; \
    curl -fsSL https://archive.apache.org/dist/tomcat/tomcat-8/v8.5.100/bin/apache-tomcat-8.5.100.tar.gz -o /tmp/tomcat.tar.gz; \
    tar -xzf /tmp/tomcat.tar.gz -C /opt; \
    mv /opt/apache-tomcat-8.5.100 ${CATALINA_HOME}; \
    rm -f /tmp/tomcat.tar.gz; \
    rm -rf ${CATALINA_HOME}/webapps/*; \
    mkdir -p ${GHOST_HOME}/app ${GHOST_HOME}/bridge ${GHOST_HOME}/data/themes ${GHOST_HOME}/shared ${GHOST_HOME}/worker ${GHOST_HOME}/bundle ${GHOST_HOME}/run/bundle ${GHOST_HOME}/run/web ${CATALINA_HOME}/conf/Catalina/localhost ${CATALINA_HOME}/webapps/ROOT/WEB-INF/classes ${CATALINA_HOME}/webapps/ROOT/WEB-INF/lib /build/protocol-classes /build/web-classes /build/worker-classes; \
    TOMCAT_CP=$(find ${CATALINA_HOME}/lib ${CATALINA_HOME}/bin -maxdepth 1 -name '*.jar' -printf '%p:' | sed 's/:$//'); \
    javac -encoding UTF-8 -d /build/protocol-classes $(find /build/source/src/main/java/ctf/ghostvalve/model /build/source/src/main/java/ctf/ghostvalve/spi /build/source/src/main/java/ctf/ghostvalve/protocol -name '*.java' | sort); \
    jar cf ${GHOST_HOME}/shared/protocol.jar -C /build/protocol-classes .; \
    javac -encoding UTF-8 -cp ${GHOST_HOME}/shared/protocol.jar:${TOMCAT_CP} -d /build/web-classes $(find /build/source/src/main/java -name '*.java' ! -path '*/ctf/ghostvalve/model/*' ! -path '*/ctf/ghostvalve/spi/*' ! -path '*/ctf/ghostvalve/protocol/*' ! -path '*/ctf/ghostvalve/vault/*' | sort); \
    cp -r /build/source/src/webapp/. ${CATALINA_HOME}/webapps/ROOT/; \
    cp -r /build/web-classes/. ${CATALINA_HOME}/webapps/ROOT/WEB-INF/classes/; \
    cp ${GHOST_HOME}/shared/protocol.jar ${CATALINA_HOME}/webapps/ROOT/WEB-INF/lib/protocol.jar; \
    javac -encoding UTF-8 -cp ${GHOST_HOME}/shared/protocol.jar -d /build/worker-classes $(find /build/source/src/worker/java -name '*.java' | sort); \
    printf 'Manifest-Version: 1.0\nMain-Class: ctf.ghostvalve.worker.PreviewWorkerMain\n' > /build/worker.mf; \
    jar cfm ${GHOST_HOME}/worker/preview-runner.jar /build/worker.mf -C /build/worker-classes .; \
    cp /build/catalog-main.jar ${GHOST_HOME}/bundle/catalog-main.jar; \
    cp /build/studio-agent.bin ${GHOST_HOME}/bridge/ghost-helper; \
    cp /build/helper-runtime.env ${GHOST_HOME}/bridge/helper-runtime.env; \
    useradd -m -d /home/web web; \
    useradd -m -d /home/bundle bundle; \
    chown -R web:web ${GHOST_HOME}/data ${GHOST_HOME}/run/web ${CATALINA_HOME}/logs ${CATALINA_HOME}/temp ${CATALINA_HOME}/work; \
    chown -R bundle:bundle ${GHOST_HOME}/run/bundle; \
    chmod -R a+rX ${CATALINA_HOME} ${GHOST_HOME}; \
    chmod u+rwx ${GHOST_HOME}/data/themes ${GHOST_HOME}/run/web ${CATALINA_HOME}/logs ${CATALINA_HOME}/temp ${CATALINA_HOME}/work; \
    chmod u+rwx ${GHOST_HOME}/run/bundle; \
    chmod +x /entrypoint.sh ${GHOST_HOME}/bridge/ghost-helper; \
    chmod -R a-w ${CATALINA_HOME}/webapps/ROOT

EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]
