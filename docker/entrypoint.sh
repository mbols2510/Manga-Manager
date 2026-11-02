#!/bin/sh
# Runs manga_manager as PUID:PGID (set in docker-compose.yml) so files it writes on
# the NAS are owned by your own user instead of root.
set -e

PUID="${PUID:-1000}"
PGID="${PGID:-1000}"
umask "${UMASK:-022}"

mkdir -p /data /config
if [ ! -f "${MANGA_MANAGER_CONFIG:-/config/config.yml}" ] && [ -w /config ]; then
  cp /app/config.example.yml /config/config.example.yml 2>/dev/null || true
fi

if [ "$(id -u)" = "0" ]; then
  chown -R "$PUID:$PGID" /data 2>/dev/null || true
  chown "$PUID:$PGID" /config 2>/dev/null || true
  echo "Starting manga_manager as uid=$PUID gid=$PGID"
  exec setpriv --reuid="$PUID" --regid="$PGID" --clear-groups -- python -m manga_manager "$@"
fi

exec python -m manga_manager "$@"
