#!/bin/sh
set -eu
cd /home/sym/mei
chmod 600 deployment/.env
docker compose --env-file deployment/.env -f deployment/docker-compose.yml up -d
docker compose --env-file deployment/.env -f deployment/docker-compose.yml ps
