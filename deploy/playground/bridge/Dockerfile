# The autonomous bridge for the public playground: babashka, the bridge
# source and its one Maven dependency (malli). No JVM, no package manager, no
# nREPL client in use: the config serves one websocket target only.
#
# Staged by deploy/playground/stage-bridge.sh into target/playground-bridge:
#   deploy/playground/stage-bridge.sh
#   docker build -f deploy/playground/bridge/Dockerfile -t ghcr.io/buddhilw/autonomous-bridge:<tag> target/playground-bridge

FROM ubuntu:26.04
COPY bb /usr/local/bin/bb
COPY m2 /opt/m2
COPY classpath /app/classpath
COPY src /app/src
COPY entrypoint.sh /app/entrypoint.sh
ENV HOME=/tmp
USER 65532:65532
WORKDIR /tmp
# 7890: runtimes dial in (in-cluster only). 7891: MCP over HTTP (public).
EXPOSE 7890 7891
ENTRYPOINT ["/app/entrypoint.sh"]
