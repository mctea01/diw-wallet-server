# Runtime image only: build the distribution first with ./gradlew installDist (CI does this); on linux-x86_64 it
# carries its jlink runtime, so the image needs no Gradle, Android SDK, JDK or Rust toolchain. Ubuntu 24.04 matches
# the glibc the native proof library is built against on CI.
FROM ubuntu:24.04
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --home-dir /var/lib/diw-server --shell /usr/sbin/nologin diw \
    && mkdir -p /var/lib/diw-server && chown diw:diw /var/lib/diw-server
COPY build/install/diw-server /opt/diw-server
# Class data sharing archive from one training start, roughly halving start time and the resident memory after start
# (measured 1.5 s to 0.8 s and 153 MB to 103 MB); the JVM ignores it if the classpath ever differs.
RUN set -e; \
    test -x /opt/diw-server/runtime/bin/java; \
    mkdir -p /tmp/cds; \
    DIW_PUBLIC_URL=https://cds.invalid DIW_DATA_DIR=/tmp/cds DIW_LISTEN_HOST=127.0.0.1 DIW_LISTEN_PORT=18080 \
        DIW_FEATURES=relay JDK_JAVA_OPTIONS=-XX:ArchiveClassesAtExit=/opt/diw-server/diw-server.jsa \
        /opt/diw-server/bin/diw-server & pid=$!; \
    for i in $(seq 1 300); do curl -fs http://127.0.0.1:18080/healthz >/dev/null && break; sleep 0.1; done; \
    curl -fs http://127.0.0.1:18080/info >/dev/null; \
    kill -TERM $pid; wait $pid || true; \
    test -s /opt/diw-server/diw-server.jsa; \
    rm -rf /tmp/cds
USER diw
ENV DIW_LISTEN_HOST=0.0.0.0 \
    DIW_LISTEN_PORT=8080 \
    DIW_DATA_DIR=/var/lib/diw-server \
    JDK_JAVA_OPTIONS=-XX:SharedArchiveFile=/opt/diw-server/diw-server.jsa
VOLUME /var/lib/diw-server
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["/opt/diw-server/bin/diw-server"]
