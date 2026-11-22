#!/usr/bin/env bash
# Stage the bridge image's files into target/playground-bridge: babashka
# (static, checksum-pinned), the bridge source, its Maven dependencies
# resolved on this host (bb + a JDK), and the classpath they make inside the
# image. The image build then needs no network and no package manager.
set -euo pipefail
cd "$(dirname "$0")/../.."
BB_VERSION=1.13.224
BB_SHA256=aa55a3a2cacdfc2c8eba38991cdd4ea3d37eadde9ba374f0da0b95ad00daa1ba
out=target/playground-bridge
rm -rf "$out"
mkdir -p "$out/m2"

curl -fsSL -o "$out/bb.tgz" \
  "https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-amd64-static.tar.gz"
echo "${BB_SHA256}  $out/bb.tgz" | sha256sum -c -
tar -xzf "$out/bb.tgz" -C "$out" bb
rm "$out/bb.tgz"

# Resolve the dependencies into $out/m2, then point the classpath at /opt/m2.
# bb-mcp carries the nREPL client autonomous.bridge.channel loads on
# babashka; the playground config never uses it (one websocket target).
m2=$(cd "$out/m2" && pwd)
cat > "$out/bb.edn" <<EOF
{:paths [] :deps {metosin/malli {:mvn/version "0.20.3"}
                  io.github.hive-agi/bb-mcp {:mvn/version "1.3.7"}}
 :mvn/local-repo "$m2"}
EOF
(cd "$out" && ./bb -e '(println (babashka.classpath/get-classpath))') | sed "s#$m2#/opt/m2#g; s#^#/app/src:#" > "$out/classpath"
rm "$out/bb.edn"
find "$out/m2" -type f ! -name '*.jar' -delete

cp -r src "$out/src"
cp -r bridge/src/. "$out/src/"
cp deploy/playground/bridge/entrypoint.sh "$out/entrypoint.sh"
echo "classpath: $(cat "$out/classpath")"
