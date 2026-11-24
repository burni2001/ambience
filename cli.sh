#!/bin/bash
# Kern auf dem Mac ausprobieren, ohne die TV-App. Liest ../tv.env und ../hue.env.
# Aufruf: ./huesync/cli.sh <Befehl> ...   (ohne Befehl: Liste der Befehle)
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
OUT="$DIR/build/cli"

mkdir -p "$OUT"
find "$DIR/core" "$DIR/cli" -name '*.java' -print0 |
  xargs -0 javac -source 8 -target 8 -Xlint:-options -d "$OUT"

exec java -cp "$OUT" -Dhuesync.env="$DIR/../tv.env:$DIR/../hue.env" tv.ambience.sync.cli.Cli "$@"
