#!/bin/sh
set -eu
caddy validate --config /tmp/Caddyfile.farmap
if [ ! -f /etc/caddy/Caddyfile.bak-before-farmap ]; then
  cp -p /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak-before-farmap
fi
install -o root -g root -m 644 /tmp/Caddyfile.farmap /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile
systemctl reload caddy
systemctl is-active caddy
