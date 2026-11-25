# The autonomous bridge for the public playground: static babashka, the
# bridge source and its Maven jars, on an image with no shell and no package
# manager. No JVM, no nREPL client in use: the config serves one websocket
# target only.
#
# Staged by deploy/playground/stage-bridge.sh into target/playground-bridge,
# which also prints the build command (the classpath goes in as a build arg):
#   deploy/playground/stage-bridge.sh
#   docker build -f deploy/playground/bridge/Dockerfile \
#     --build-arg BRIDGE_CLASSPATH="$(cat target/playground-bridge/classpath)" \
#     -t ghcr.io/buddhilw/autonomous-bridge:<tag> target/playground-bridge
#
# FROM scratch: bb is a static (musl) binary, so the image holds bb, /app/src
# and /opt/m2 and nothing else. The entrypoint is exec form; babashka reads
# the classpath from BABASHKA_CLASSPATH, so no shell assembles the command.

FROM scratch
ARG BRIDGE_CLASSPATH
COPY bb /usr/local/bin/bb
COPY m2 /opt/m2
COPY src /app/src
ENV HOME=/tmp \
    BABASHKA_CLASSPATH=${BRIDGE_CLASSPATH}
USER 65532:65532
WORKDIR /tmp
# 7890: runtimes dial in (in-cluster only). 7891: MCP over HTTP (public).
EXPOSE 7890 7891
ENTRYPOINT ["/usr/local/bin/bb", "-m", "autonomous.cli", "serve", "--config", "/etc/autonomous/autonomous.edn", "--http", "0.0.0.0:7891"]
