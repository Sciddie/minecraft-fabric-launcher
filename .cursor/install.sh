#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER=/home/ubuntu/fabriclauncher-test
JAR="$SERVER/spigot-26.3.jar"
SHA=59f4a101e03012e5182e43c77f06679cdcb15d779c7c4ab6899200c567d79537
URL=https://getbukkit.org/api/jars/spigot/26.3/download

mkdir -p "$SERVER"
if [[ ! -f "$JAR" ]] || ! echo "$SHA  $JAR" | sha256sum -c - >/dev/null 2>&1; then
  curl -fsSL -o "$JAR.partial" "$URL"
  echo "$SHA  $JAR.partial" | sha256sum -c -
  mv "$JAR.partial" "$JAR"
fi

mvn -B -DskipTests package
