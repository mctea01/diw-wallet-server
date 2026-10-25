# Runtime image only: build the distribution first with ./gradlew installDist (CI does this), so the
# image needs no Gradle, Android SDK or Rust toolchain and builds in seconds.
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --home-dir /var/lib/diw-server --shell /usr/sbin/nologin diw \
    && mkdir -p /var/lib/diw-server && chown diw:diw /var/lib/diw-server
COPY build/install/diw-server /opt/diw-server
USER diw
ENV DIW_LISTEN_HOST=0.0.0.0 \
    DIW_LISTEN_PORT=8080 \
    DIW_DATA_DIR=/var/lib/diw-server
VOLUME /var/lib/diw-server
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["/opt/diw-server/bin/diw-server"]
