# The autonomous bridge for the public playground: babashka, the bridge
# source and its one Maven dependency (malli). No JVM, no shell tools, no
# nREPL client in use: the config serves one websocket target only.
#
# Build from the repository root:
#   docker build -f deploy/playground/bridge/Dockerfile -t ghcr.io/buddhilw/autonomous-bridge:<tag> .

FROM ubuntu:26.04 AS deps
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl default-jre-headless \
 && rm -rf /var/lib/apt/lists/*
ARG BB_VERSION=1.13.224
ARG BB_SHA256=aa55a3a2cacdfc2c8eba38991cdd4ea3d37eadde9ba374f0da0b95ad00daa1ba
RUN curl -fsSL -o /tmp/bb.tgz \
      "https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-amd64-static.tar.gz" \
 && echo "${BB_SHA256}  /tmp/bb.tgz" | sha256sum -c - \
 && tar -xzf /tmp/bb.tgz -C /usr/local/bin bb \
 && rm /tmp/bb.tgz
WORKDIR /build
COPY deploy/playground/bridge/bb.edn bb.edn
# Resolve the dependencies once, here; the runtime image only reads them.
RUN bb -e '(println (babashka.classpath/get-classpath))' > classpath \
 && cat classpath

FROM ubuntu:26.04
COPY --from=deps /usr/local/bin/bb /usr/local/bin/bb
COPY --from=deps /opt/m2 /opt/m2
COPY --from=deps /build/classpath /app/classpath
COPY src /app/src
COPY deploy/playground/bridge/entrypoint.sh /app/entrypoint.sh
ENV HOME=/tmp
USER 65532:65532
WORKDIR /tmp
# 7890: runtimes dial in (in-cluster only). 7891: MCP over HTTP (public).
EXPOSE 7890 7891
ENTRYPOINT ["/app/entrypoint.sh"]
