# FarMap 答辩可用性复核

日期：2026-09-09

## 结论

核心产品链路已在当前本地环境跑通：

`Farm / Field / Camera → Diagnosis Job → 多模态 RAG → Diagnosis → Agent Run → 人工审批 → Operations Task`

当前状态为：**答辩演示可用；外部 Hunyuan Vision 适配器仍为可选降级项。** 图片诊断主链路已经使用 Qwen VL 完成，未因 Hunyuan 配置缺失而阻断。

## 本轮整改

- Diagnosis 改为后台 Job，页面显示排队、RAG、模型生成和完成状态，不再让浏览器长时间等待一个同步请求。
- 本地上传图片在跨服务调用前转为受限 data URI；浏览器保留原上传 URL，视觉、CLIP 和百炼模型不再收到 Docker 内部不可访问的地址。
- Hunyuan Vision 不可用时，仍保留真实图像的 CLIP 证据，Agent 不再把已经上传的图像误判为“未提供”。
- 已持久化 Diagnosis 的 Agent 上下文从相机 Evidence 恢复图片 URL，且不再重复调用无效的规划模型请求。
- Qwen 文本证据合成显式关闭隐藏思考，减少无意义的多分钟等待；多模态推理模型仍保留其模型策略。
- Copilot 过期会话自动清理并重试；“分析这个地块”共用 Diagnosis Job 与 Agent Run 链路。
- Operations 任务标题改为真实地块名称，审批后保持 `diagnosisId / recommendationId / agentRunId / evidenceIds` 关系。

## 真实运行验收

### 1. Intelligence Plane

`GET /api/runtime/health`：后端、Local AI、BGE 文本向量、CLIP 图像向量、知识库、Qwen 知识向量和 GTE 重排均为 READY；Milvus 19530 可用，知识集合存在并有 507 个 chunk。

### 2. 多模态 Diagnosis

真实 Job：`diagnosis-job-f6d4b0f4-9db1-42ce-83d2-b7ebc09d226e`

结果：`COMPLETED`，Diagnosis：`diagnosis-5fc890c3-404c-4cfa-b5d1-af5af7bc0801`，总耗时约 101 秒，包含 1 条 camera、1 条 spatial、3 条 knowledge Evidence，生成 3 条 Claim 和 3 条 Recommendation。

模型与向量链路：

- 多模态推理：`qwen3-vl-32b-thinking`
- 知识向量：`qwen3.7-text-embedding-flash`，1024 维
- 知识重排：`gte-rerank-v2`
- 图像向量：`clip-image-encoder.onnx`，512 维，Local AI

### 3. Agent → Operations

真实 Agent：`agent-2179538a-f36a-4639-9657-c1344c294132`

结果：约 2 秒进入 `WAITING_FOR_APPROVAL`，携带 1 张诊断图片并执行 camera tool；人工审批后变为 `COMPLETED`，生成真实任务：

- 标题：`复核 A-12 云阳柑橘示范园 农情`
- 状态：`OPEN`
- 优先级：`HIGH`
- 绑定 Diagnosis、Recommendation、Agent Run 和 Evidence

### 4. 摄像头与地图点

当前真实摄像头接口验证结果：

- 巫山：24 路摄像头，24 路 `BOUND`，0 路未绑定
- L-01 核心区：6 路
- L-02 西部区：7 路
- L-03 中部区：7 路
- L-04 东部区：2 路
- L-05 南部区：1 路
- L-06 北部区：1 路

每路摄像头均有 `farmId + fieldId + pointId + longitude + latitude`，地图点击和摄像头选择共用同一上下文。云阳、大足当前没有这 24 路巫山摄像头，这是按真实所属农场隔离后的正确结果，不是丢失。

## 验证命令

- Java：`mvn -q -Dtest=AgentPlannerTest,AgentRuntimeTest,AgentOperationsIntegrationTest,DiagnosisAgentLinkTest,CopilotAgentEscalationTest test`
- 前端：`npm run build`
- 本轮结果：Java 定向测试通过；前端 7178 个模块构建通过。

## 已知条件

1. Hunyuan Vision Flask 进程可以启动，但本机未配置 `HUNYUAN_API_KEY`，所以专用 Hunyuan 视觉观察接口会降级；这不阻断 Qwen VL 多模态诊断和 CLIP 图像证据。
2. 当前历史案例向量库为 `READY_EMPTY`，因此 Agent 不会伪造历史病例；知识库检索使用已导入的管理员农业知识，并保留来源、版本和 chunk ID。
3. 答辩演示字段的空间分区使用 `SYNTHETIC_PRESENTATION` 标识，真实摄像头序列号、直播地址和农场归属仍保持真实，不把演示拓扑冒充现场测绘结果。

