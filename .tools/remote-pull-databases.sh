#!/bin/sh
set -eu
cd /home/sym/mei
nohup docker compose --env-file deployment/.env -f deployment/docker-compose.yml pull mysql mongo > deployment/pull.log 2>&1 &
echo $! > deployment/pull.pid
echo "database pull started: $!"
