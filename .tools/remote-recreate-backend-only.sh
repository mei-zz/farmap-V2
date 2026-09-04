#!/bin/sh
set -eu
cd /home/sym/mei
docker compose --env-file deployment/.env -f deployment/docker-compose.yml up -d --no-deps --force-recreate backend
