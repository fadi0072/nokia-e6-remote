#!/bin/bash
# Downloads the build tools into ./tools (no admin rights needed).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p tools && cd tools
if [ ! -d jdk8 ]; then
  echo "Downloading JDK 8 (Azul Zulu, Apple Silicon)..."
  URL=$(curl -s 'https://api.azul.com/metadata/v1/zulu/packages/?java_version=8&os=macos&arch=aarch64&archive_type=tar.gz&java_package_type=jdk&latest=true&release_status=ga&page_size=1' \
        | python3 -c 'import json,sys;print(json.load(sys.stdin)[0]["download_url"])')
  curl -L -o jdk8.tar.gz "$URL" && mkdir jdk8 && tar -xzf jdk8.tar.gz -C jdk8 --strip-components=1 && rm jdk8.tar.gz
fi
for a in cldcapi11 midpapi20; do
  [ -f $a.jar ] || curl -sL -o $a.jar "https://repo1.maven.org/maven2/org/microemu/$a/2.0.4/$a-2.0.4.jar"
done
if [ ! -d proguard ]; then
  echo "Downloading ProGuard..."
  curl -L -o pg.tar.gz https://github.com/Guardsquare/proguard/releases/download/v7.10.0/proguard-7.10.0.tar.gz
  mkdir proguard && tar -xzf pg.tar.gz -C proguard --strip-components=1 && rm pg.tar.gz
fi
echo "Tools ready."
