# The todo backend for the public playground: the Rust binary, libautonomous
# and the built Elm UI. Built on the host (same glibc as ubuntu:26.04) and
# staged by deploy/playground/stage-backend.sh into target/playground.
#
#   deploy/playground/stage-backend.sh
#   docker build -f deploy/playground/backend/Dockerfile -t ghcr.io/buddhilw/autonomous-todo:<tag> target/playground

FROM ubuntu:26.04
COPY todo-backend /app/todo-backend
COPY libautonomous.so /app/lib/libautonomous.so
COPY dist /app/dist
ENV LD_LIBRARY_PATH=/app/lib \
    TODO_BIND=0.0.0.0:8090 \
    TODO_STATIC=/app/dist \
    HOME=/tmp
USER 65532:65532
WORKDIR /tmp
EXPOSE 8090
ENTRYPOINT ["/app/todo-backend"]
