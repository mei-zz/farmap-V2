#!/bin/sh
set -u
echo CONTAINERS
docker ps -a
echo IMAGES
docker images
echo COMPOSE
docker compose ls
echo DOCKER_ROOT
docker info --format '{{.DockerRootDir}}'
echo PROXY_PROCESSES
ps aux | grep -E 'caddy|nginx' | grep -v grep || true
echo CADDY_FILES
find /etc/caddy /home/sym -maxdepth 3 -type f \( -name Caddyfile -o -name '*.caddy' \) -print 2>/dev/null || true
echo NGINX_SITES
find /etc/nginx -maxdepth 3 -type f -print 2>/dev/null || true
