#!/bin/sh
ps -eo pid,etime,pcpu,pmem,cmd | grep -E 'buildkit|mvn|pnpm|pip|docker compose' | grep -v grep | tail -20 || true
df -h /
docker image ls --filter 'reference=farmap*'
du -sh /var/lib/docker/buildkit 2>/dev/null || true
