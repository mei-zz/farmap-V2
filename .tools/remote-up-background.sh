#!/bin/sh
set -eu
cd /home/sym/mei
chmod 600 deployment/.env
nohup docker compose --env-file deployment/.env -f deployment/docker-compose.yml up -d > deployment/up.log 2>&1 &
echo $! > deployment/up.pid
echo "stack startup started: $!"
