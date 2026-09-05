# FarMap V2 本地开发

## 依赖

- Windows 10/11 与 Docker Desktop（Linux containers）
- Node.js、pnpm
- Java 17；项目优先使用 `.tools/apache-maven-3.9.16`
- `D:\anaconda\envs\llm\python.exe`
- 本地缓存的 `BAAI/bge-small-zh-v1.5` 与项目内忽略提交的 CLIP ONNX
- 本地 MySQL、MongoDB；连接信息只放在根目录 `.env`

`.env` 必须保持 Git ignored。可从 `.env.example` 复制变量名，不要把真实密钥写入源码或文档。

## 启动顺序

1. 启动 Docker Desktop。
2. 在项目根目录执行 `docker compose -f zhgy/zhgy/docker-compose-milvus.yml up -d`，启动 etcd、MinIO、Milvus。
3. Terminal 1 执行 `scripts\run-local-ai.ps1`。
4. Terminal 2 执行 `scripts\run-backend-local.ps1`。
5. Terminal 3 执行 `scripts\run-frontend-local.ps1 -RagMode real`。
6. 浏览器打开 `http://127.0.0.1:5173/farmap/`。

本地默认使用 `MILVUS_HOST=127.0.0.1`、`MILVUS_PORT=19530`、`LOCAL_AI_BASE_URL=http://127.0.0.1:8001`。服务器 Compose 可改成 `MILVUS_HOST=milvus` 和 `LOCAL_AI_BASE_URL=http://local-ai:8001`，无需改代码。

## 健康检查

- Local AI：`GET http://127.0.0.1:8001/health`
- Backend runtime：`GET http://127.0.0.1:8080/api/runtime/health`
- Milvus 只读检查：`D:\anaconda\envs\llm\python.exe scripts\validate-milvus-runtime.py`
- Local AI 完整验证：`D:\anaconda\envs\llm\python.exe scripts\validate-local-ai-runtime.py`

Local AI 只有在 BGE、CLIP 均已加载并完成 512 维 warmup 后才返回 `status=ok`。知识检索在 Local AI 不可用时降级为 lexical；历史视觉检索不可用时标记 `BLOCKED`，诊断的 camera、weather、knowledge 仍继续执行。

## Real RAG Mode

`run-frontend-local.ps1 -RagMode real` 只改变本地 Vite 进程，不修改生产默认环境。诊断页面技术详情展示 embedding provider、维度、Milvus/历史检索状态、Qwen 路由和各阶段延迟。A-12 页面当前使用产品演示上下文中的图片和天气作为输入，因此输入来源为 `DEMO`；BGE、CLIP、Milvus 连通性和 Qwen 推理的运行状态分别按实际结果标记。

## 常见错误

- `Local AI 503/degraded`：确认 BGE 缓存和 CLIP ONNX 存在，查看 `.runtime/local-ai.err.log`。
- `Milvus BLOCKED`：检查 etcd、MinIO、Milvus 三个容器；不要删除 volumes。
- `HISTORICAL_VECTOR_DATA_EMPTY`：集合不存在或 rows=0。该状态不是程序故障，不要生成假案例。
- 后端数据库连接失败：检查本地 MySQL/MongoDB 是否启动及 `.env` 中的连接变量。
- 百炼调用失败：只检查 `.env` 是否设置 key/base URL；不要在终端或日志打印真实值。
