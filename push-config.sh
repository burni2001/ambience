#!/bin/bash
# Legt die Zugangsdaten für die TV-App ab: aus ../tv.env und ../hue.env wird
# files/huesync.conf im privaten Speicher der App (per „adb run-as“, die Werte
# erscheinen in keiner Befehlszeile und in keiner Ausgabe). Danach liest die App neu.
# Weitere Zeilen (z. B. BRIGHTNESS=1.3, SMOOTHING=0.4, FPS=20) als Argumente anhängen:
#   ./huesync/push-config.sh BRIGHTNESS=1.3
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
. "$DIR/../tv.env"
. "$DIR/../hue.env"
export ANDROID_SERIAL="$TV_IP:5555"
PKG=tv.ambience.sync

adb connect "$ANDROID_SERIAL" >/dev/null 2>&1
{
  # Die App läuft auf dem TV selbst, deshalb die API über 127.0.0.1.
  printf 'TV_HOST=127.0.0.1\nTV_USER=%s\nTV_PASS=%s\n' "$TV_USER" "$TV_PASS"
  printf 'HUE_IP=%s\nHUE_KEY=%s\nHUE_CLIENTKEY=%s\n' "$HUE_IP" "$HUE_KEY" "$HUE_CLIENTKEY"
  for extra in "$@"; do printf '%s\n' "$extra"; done
} | adb shell "run-as $PKG sh -c 'mkdir -p files && cat > files/huesync.conf && chmod 600 files/huesync.conf'"

adb shell am start -n "$PKG/.ControlActivity" -a "$PKG.RELOAD" >/dev/null
echo "Einstellungen abgelegt ($(adb shell "run-as $PKG sh -c 'wc -l < files/huesync.conf'" | tr -d '[:space:]') Zeilen), App liest neu."
