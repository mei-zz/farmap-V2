#!/bin/sh
set -eu
cd /home/sym/mei
nohup docker compose --env-file deployment/.env -f deployment/docker-compose.yml up -d --force-recreate minio milvus backend > deployment/recreate-milvus.log 2>&1 &
echo $! > deployment/recreate-milvus.pid
echo "milvus/backend recreation started: $!"
