#!/bin/sh
set -u
cd /home/sym/mei
set -a
. deployment/.env
set +a

echo MILVUS_HEALTH
docker exec farmap-milvus-1 curl -fsS http://127.0.0.1:9091/healthz || true
printf '\n'
echo MILVUS_LOG_TAIL
docker logs --tail 60 farmap-milvus-1 2>&1
echo MONGO_DATABASES
docker exec farmap-mongo-1 mongo --quiet --username root --password "$MONGO_ROOT_PASSWORD" --authenticationDatabase admin --eval "db.adminCommand({listDatabases:1}).databases.forEach(function(d){print(d.name + ':' + d.sizeOnDisk)})" 2>/dev/null
echo MONGO_ALL_COLLECTIONS
docker exec farmap-mongo-1 mongo --quiet --username root --password "$MONGO_ROOT_PASSWORD" --authenticationDatabase admin --eval "db.adminCommand({listDatabases:1}).databases.forEach(function(d){var x=db.getSiblingDB(d.name);print(d.name + '=' + x.getCollectionNames().sort().join(','))})" 2>/dev/null
