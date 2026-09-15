#!/bin/sh
set -eu
cd /home/sym/mei
nohup docker compose --env-file deployment/.env -f deployment/docker-compose.yml build --progress=plain tree-analyzer frontend > deployment/runtime-build.log 2>&1 &
echo $! > deployment/runtime-build.pid
echo "runtime build started: $!"
