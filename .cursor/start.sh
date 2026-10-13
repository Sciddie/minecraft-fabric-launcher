#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER=/home/ubuntu/fabriclauncher-test
JAR="$SERVER/spigot-26.3.jar"

if (echo >/dev/tcp/127.0.0.1/2060) >/dev/null 2>&1; then
  exit 0
fi

bash .cursor/install.sh

VER=$(python3 -c 'import re; print(re.search(r"<version>([^<]+)</version>", open("pom.xml").read()).group(1))')
rm -f "$SERVER/plugins"/FabricLauncher-*.jar
mkdir -p "$SERVER/plugins/FabricLauncher"
cp "target/FabricLauncher-${VER}.jar" "$SERVER/plugins/"
printf 'eula=true\n' > "$SERVER/eula.txt"

if [[ ! -f "$SERVER/server.properties" ]]; then
  cp spigot-minimal-config/server.properties "$SERVER/server.properties"
fi

if [[ ! -f "$SERVER/plugins/FabricLauncher/config.yml" ]]; then
  python3 - << PY
from pathlib import Path
text = Path("src/main/resources/config.yml").read_text()
text = text.replace("autoStart: true", "autoStart: false", 1)
text = text.replace("  xms: 8G", "  xms: 1G", 1)
text = text.replace("  xmx: 8G", "  xmx: 1G", 1)
Path("$SERVER/plugins/FabricLauncher/config.yml").write_text(text)
PY
fi

cd "$SERVER"
exec java -Xms512M -Xmx1G -jar "$JAR" nogui
