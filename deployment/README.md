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

On the Linux deployment host:

```sh
VITE_API_URL=/api NODE_OPTIONS=--max-old-space-size=4096 pnpm build
```

`pnpm build` reads the sibling `deployment/.env` only for `GAODE_API_KEY` and
passes it to Vite as `VITE_GAODE_API_KEY`; do not copy the key into a tracked
frontend `.env` file. An explicitly supplied `VITE_GAODE_API_KEY` still takes
precedence for CI/CD.

The production Agent / multimodal-RAG path uses Alibaba Cloud Model Studio
(Bailian): `qwen3-vl-32b-thinking` for image understanding, Qwen grounded
generation, and `qwen3.7-text-embedding-flash` for the imported knowledge
vectors. The legacy `tree-analyzer` and `HUNYUAN_*` settings are retained only
for compatibility endpoints; they are not required by `/diagnosis/jobs` or the
Agent workspace. Keep the Bailian key in `BAILIAN_API_KEY` (or the existing
`APIKEY` alias) and do not put it in frontend files.

Persistent state is stored in Docker volumes, except migrated MongoDB files in
`deployment/data/mongo`. Do not delete these paths during upgrades.
