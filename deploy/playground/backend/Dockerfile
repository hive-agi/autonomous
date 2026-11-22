# The todo backend for the public playground: the Rust binary, libautonomous
# and the built Elm UI, on an image with no shell and no package manager.
# Built on the host (same glibc as ubuntu:26.04) and staged by
# deploy/playground/stage-backend.sh into target/playground.
#
#   deploy/playground/stage-backend.sh
#   docker build -f deploy/playground/backend/Dockerfile -t ghcr.io/buddhilw/autonomous-todo:<tag> target/playground
#
# The final image is FROM scratch: the dynamic loader and the three glibc
# libraries the binary and libautonomous.so link (libc, libm, libgcc_s),
# copied from a pinned ubuntu:26.04, and the app. Nothing else: no /bin/sh,
# no coreutils, no apt. `ldd` on both ELF files lists exactly these.

FROM ubuntu:26.04@sha256:d66b366dac61b575bee2aef07e9c26e8070096fbe84a3d4d3eb0da069b5d76db AS glibc

FROM scratch
COPY --from=glibc /usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 /lib64/ld-linux-x86-64.so.2
COPY --from=glibc /usr/lib/x86_64-linux-gnu/libc.so.6 \
                  /usr/lib/x86_64-linux-gnu/libm.so.6 \
                  /usr/lib/x86_64-linux-gnu/libgcc_s.so.1 \
                  /usr/lib/x86_64-linux-gnu/
COPY todo-backend /app/todo-backend
COPY libautonomous.so /app/lib/libautonomous.so
# Only the page's files (statics::ALLOWED); the backend serves nothing else.
COPY dist/index.html dist/app.js dist/autonomous.js dist/elm.js dist/style.css /app/dist/
ENV LD_LIBRARY_PATH=/app/lib:/usr/lib/x86_64-linux-gnu \
    TODO_BIND=0.0.0.0:8090 \
    TODO_STATIC=/app/dist \
    HOME=/tmp
USER 65532:65532
WORKDIR /tmp
EXPOSE 8090
ENTRYPOINT ["/app/todo-backend"]
