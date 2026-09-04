# FarMap production deployment

The complete stack is managed from `/home/sym/mei/deployment`:

```sh
sudo docker compose --env-file .env up -d --build
sudo docker compose --env-file .env ps
sudo docker compose --env-file .env logs -f backend tree-analyzer
```

The public application entry point is `https://map.archivemodel.cn/farmap/`.
The frontend Nginx container listens on host loopback port `24080`; the host
Caddy gateway owns ports 80/443 and proxies the domain to that container.
Nginx serves the React SPA below `/farmap/` and proxies `/api/*` to Spring Boot.
The bare IP entry point is `http://175.27.171.40/farmap/`.

Before rebuilding the prebuilt frontend image, create its production bundle:

```powershell
$env:VITE_API_URL='/api'
$env:NODE_OPTIONS='--max-old-space-size=4096'
pnpm build
```

The visual analyzer uses `hunyuan-turbos-vision`. The Spring JSON editing
service uses `hunyuan-a13b`, selected through `HUNYUAN_TEXT_MODEL` because the
previous `hunyuan-lite` model has been retired.

Persistent state is stored in Docker volumes, except migrated MongoDB files in
`deployment/data/mongo`. Do not delete these paths during upgrades.
