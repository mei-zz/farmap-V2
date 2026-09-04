#!/bin/sh
set -eu
cd /home/sym/mei
nohup docker compose --env-file deployment/.env -f deployment/docker-compose.yml build --progress=plain > deployment/build.log 2>&1 &
echo $! > deployment/build.pid
echo "build started pid=$!"
