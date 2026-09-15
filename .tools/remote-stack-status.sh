#!/bin/sh
set -u
cd /home/sym/mei
echo PROCESSES
ps -eo pid,etime,stat,args | grep -E 'docker compose.*farmap|docker pull' | grep -v grep || true
echo COMPOSE
docker compose --env-file deployment/.env -f deployment/docker-compose.yml ps -a || true
echo CONTAINERS
docker ps -a --filter name=farmap --format '{{.Names}} | {{.Status}} | {{.Image}}' || true
echo IMAGES
docker image ls --format '{{.Repository}}:{{.Tag}} | {{.Size}}' | grep -E 'farmap|mysql|mongo|milvusdb/milvus|minio/minio|quay.io/coreos/etcd' || true
echo DISK
df -h /
