#!/bin/sh
set -eu
cd /home/sym/mei
nohup sh -c 'docker compose --env-file deployment/.env -f deployment/docker-compose.yml build --progress=plain backend && docker compose --env-file deployment/.env -f deployment/docker-compose.yml up -d --no-deps --force-recreate backend' > deployment/backend-rebuild.log 2>&1 &
echo $! > deployment/backend-rebuild.pid
echo "backend rebuild started: $!"
