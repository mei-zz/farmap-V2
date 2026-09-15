#!/bin/sh
set -u
cd /home/sym/mei
set -a
. deployment/.env
set +a

echo HTTP
curl -sS -m 15 -o /tmp/farmap-front.html -w 'frontend_status=%{http_code}\n' http://127.0.0.1:24080/
curl -sS -m 15 -o /tmp/farmap-health.json -w 'backend_health_status=%{http_code}\n' http://127.0.0.1:24009/health
printf 'backend_health_body='
cat /tmp/farmap-health.json
printf '\n'

echo MYSQL_COUNTS
docker exec farmap-mysql-1 mysql -N -uzhgy -p"$MYSQL_PASSWORD" zhgy -e "SELECT 'user',COUNT(*) FROM user UNION ALL SELECT 'farm',COUNT(*) FROM farm UNION ALL SELECT 'guidances',COUNT(*) FROM guidances UNION ALL SELECT 'location',COUNT(*) FROM location UNION ALL SELECT 'crops',COUNT(*) FROM crops UNION ALL SELECT 'user_requests',COUNT(*) FROM user_requests UNION ALL SELECT 'initial_results',COUNT(*) FROM initial_results;" 2>/dev/null

echo MONGO_COLLECTIONS
docker exec farmap-mongo-1 mongo --quiet --username root --password "$MONGO_ROOT_PASSWORD" --authenticationDatabase admin zhgy --eval "db.getCollectionNames().sort().join(',')" 2>/dev/null

echo BACKEND_LOG_TAIL
docker logs --tail 80 farmap-backend-1 2>&1
