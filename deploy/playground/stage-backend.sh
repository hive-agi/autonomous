#!/usr/bin/env bash
# Stage the backend image's files into target/playground: the release
# binary, libautonomous.so and the built frontend. Run from the repo root
# after `bb cabi`, a release build of example/fullstack/backend and a
# frontend build (bb fullstack builds all three).
#   TODO_BACKEND_BIN  the backend binary [example/fullstack/backend/target/release/todo-backend]
set -euo pipefail
cd "$(dirname "$0")/../.."
bin=${TODO_BACKEND_BIN:-example/fullstack/backend/target/release/todo-backend}
out=target/playground
rm -rf "$out"
mkdir -p "$out"
cp "$bin" "$out/todo-backend"
cp cabi/build/libautonomous.so "$out/libautonomous.so"
# Only the page's files: the same list as statics::ALLOWED in the backend.
mkdir -p "$out/dist"
for f in index.html app.js autonomous.js elm.js style.css; do
  cp "example/fullstack/frontend/dist/$f" "$out/dist/$f"
done
ls -la "$out" "$out/dist"
