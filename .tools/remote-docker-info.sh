#!/bin/sh
set -u
echo DAEMON_CONFIG
if [ -f /etc/docker/daemon.json ]; then
  sed -E 's/(password|secret|token)"[[:space:]]*:[[:space:]]*"[^"]+"/\1":"***"/gi' /etc/docker/daemon.json
else
  echo none
fi
echo MIRRORS
docker info --format '{{json .RegistryConfig.Mirrors}}'
echo DATABASE_IMAGES
docker image ls --format '{{.Repository}}:{{.Tag}} | {{.Size}}' | grep -Ei 'mysql|mariadb|mongo|postgres|milvus' || true
