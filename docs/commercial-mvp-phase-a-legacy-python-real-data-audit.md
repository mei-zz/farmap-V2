# FarMap Commercial MVP Phase A

# Legacy Backend + Python AI + Real Farm Data Reality Audit & Integration Architecture

- 审查日期：2026-09-06
- 范围：完整项目根目录、Java/Spring Boot、Python、前端、配置、MySQL、MongoDB、Milvus、模型与历史案例。
- 边界：本阶段只读审查并生成文档；未修改业务代码、数据库数据或运行时配置。
- 分类：REAL=真实持久化；PACKAGED=随代码发布；DEMO=演示；TEST_FIXTURE=测试夹具；UNAVAILABLE=未实现或当前不可用。

## 1. 审查结论

**Phase A Audit：GO。Commercial MVP Integration Readiness：NO-GO。**

系统已超过 Research Prototype：MySQL 产品闭环、Diagnosis/Agent/Approval/Operations/Outcome/Expert Review 模型、Java RAG Evidence 编排和受限 Tool Calling 已存在。尚未达到 Commercial Agricultural AI SaaS 的原因是：Python 没有统一 Intelligence Contract，Field identity 不稳定，Overview 仍由 Demo ViewModel 驱动，知识上传索引未实现，Mongo/Milvus 历史案例为空，旧/新模型路径并行，Java+Python E2E 未形成运行时门禁。

只读核对结果：

- MySQL zhgy：farm=3、farmlocation=68、location=989、crops=510、agrirecord=12、weather=24、accumulated_temperature=582、user_requests=167、initial_results=27、agent_diagnoses=4、agent_operations_tasks=3、agent_operation_outcomes=2。
- Mongo zhgy：0 collection、0 objects。
- Milvus farmap_image_vectors_new：存在，512 维，IVF_FLAT/L2/nlist=128，0 rows，READY_EMPTY。
- Python 5000/8001 与 Java 8080 本次未监听。

## 2. 当前系统拓扑

React/Vite 前端调用 Spring Boot 的 /diagnosis/analyze、/api/agent/runs、/ai-model/upload、/api/expert-reviews、/api/knowledge。Spring Boot 负责控制面、MySQL、RAG、Agent、审核和 Operations，通过 LocalAiClient 调 Python Local AI embedding，通过 model.api.url 调 Python tree-analyzer；同时仍保留 Java direct Hunyuan 和 Bailian/Qwen。

目标应收敛为：

Frontend → Spring Boot Commercial Control Plane → server-side Farm/Field Context Assembly → Python Intelligence Plane → Java Evidence/Diagnosis/Agent/Operations persistence。

## 3. Python 后端 Reality Audit

### 3.1 zhgy/app.py

Flask 2.3.2，Gunicorn/Docker 默认 5000，路由为 GET /、GET /health、POST /analyze。POST /analyze 要求 X-API-Key，输入 image_urls，限制 1–5 张图片，调用 Hunyuan Vision。输出没有 diagnosisId、fieldId、evidenceId、source timestamp、content hash 或 schema version。默认 API key 是占位符 your-api-key；任意远程 URL 读取存在 SSRF 风险。本次 5000 未运行。

### 3.2 local-ai/app.py

FastAPI + Uvicorn，默认 8001；GET /health、POST /embedding/text、POST /embedding/image。BGE-small-zh-v1.5 和 CLIP ONNX 均 512 维。只负责 embedding，不负责诊断、证据融合、Agent、文档 ingestion 或 Milvus 写入。本次 8001 未运行。

### 3.3 依赖边界

zhgy/requirements.txt 没有 pymongo、pymilvus、Torch、Transformers；local-ai/requirements.txt 才包含 FastAPI、Torch、Transformers、ONNX Runtime、Pillow。scripts/*.py 的 pymilvus 代码是验证/bootstrap 工具，不是生产服务。没有发现 Python 业务服务读写 MySQL/Mongo/Milvus。

## 4. 运行时与启动

| 服务 | 入口/端口 | 启动 | 本次 |
|---|---|---|---|
| Spring Boot | zhgy/zhgy:8080 | run-backend-local.ps1 | 未监听 |
| Legacy visual | zhgy/app.py:5000 | Gunicorn/Docker | 未监听 |
| Local AI | local-ai/app.py:8001 | run-local-ai.ps1 | 未监听 |
| MySQL | localhost:3306 | 本机服务 | 可访问 |
| MongoDB | 127.0.0.1:27017 | mongodb 容器 | 可访问 |
| Milvus | 127.0.0.1:19530 | milvus-standalone | 可访问 |

尚无已验证的一键启动及完整链路健康门禁。

## 5. Java ↔ Python 集成

已存在：AIModelServiceImpl → model.api.url 默认 /analyze；LocalAiClient → /health、/embedding/text、/embedding/image；Docker backend → tree-analyzer；Java embedding providers → Python vectors。

缺失：Python 不识别 farm/field/diagnosis/evidence；输出不能直接映射 agent_diagnoses 稳定身份；Java direct Hunyuan、Flask Hunyuan、Bailian/Qwen 三条路径并行；没有统一 timeout/retry/circuit-breaker、traceId、contract version、错误码。当前是 HTTP adapter 级集成，不是商业 Intelligence Plane contract。

## 6. 数据来源总表

| 数据源 | 分类 | 事实 |
|---|---|---|
| MySQL farm | REAL | 3 行农场主数据 |
| MySQL farmlocation | REAL | 68 个位置点，不是 Field aggregate |
| MySQL location | REAL | 989 行 legacy location，语义重叠 |
| MySQL crops | REAL | 510 行、3 个 farm |
| MySQL agrirecord | REAL | 12 行，图片字段无 capture model |
| MySQL weather | REAL | 24 行月份/类型文字 |
| MySQL accumulated_temperature | REAL | 582 行时序 |
| MySQL user_requests | REAL | 167 行图片请求 |
| MySQL initial_results | REAL | 27 行 legacy snapshot |
| orchard_management.txt | PACKAGED | 3,059 bytes curated knowledge |
| frontend src/demo | DEMO | Overview/Diagnosis/Agent fallback |
| test evaluation JSON | TEST_FIXTURE | 评估夹具 |
| Mongo cases | UNAVAILABLE/EMPTY | 0 collection |
| Milvus historical collection | READY_EMPTY | 0 rows |

## 7. Farm

farm 表是当前唯一明确的 Farm 主表，含 id/name/type/address/center/user_id，真实 3 行。前端有 Farm Store/Layout 选择，但 Overview 仍以 demoFarm 为主要 ViewModel；没有统一的 Commercial Farm Context API。Farm 是 REAL，AI 上下文为 PARTIAL。

## 8. Field

没有规范 field 主表或稳定 Field aggregate。farmlocation/location 是点位，crops 以 farm_id 组织。新 DTO 虽有 fieldId，但 A-12 等主要来自 Demo/请求输入。Field identity 为 PARTIAL/DEMO，是 P0：不能用于可靠的空间风险、Evidence 归属、Agent scope 和授权。

## 9. Camera / 图片

/ai-model/upload 可上传 1–5 张 JPG/PNG（单张 10 MB），写入 ZHGY_UPLOAD_DIR；user_requests 持久化 URL，agrirecord 也有图片字段。没有 camera registry、device ownership、capture event、checksum、拍摄时间或授权模型。monitorList.ts 和 Overview monitor 是硬编码/演示。VisualEvidenceRetriever 生成的 CAM-01 等不是真实设备身份。图片上传 REAL，设备时序 DEMO/PARTIAL。

## 10. Weather

weather 是月份/类型文字，不是实时观测；accumulated_temperature 为 REAL 时序。WeatherApiService/WeatherDataScheduledTask 有高德调用但依赖 GAODE_API_KEY。getHistoricalWeatherData 明确生成随机模拟历史温度，不能作为商业事实。Diagnosis 的 weather 主要由 caller/前端提供，未按 Field 服务端查询。实时天气 PARTIAL，随机历史数据必须禁止进入真实证据。

## 11. Soil / Sensor

无土壤传感器表、设备表或 API。SoilEvidenceRetriever 只读取 request.context.soil；前端 soilMoisture/soilType 来自 Demo，Agent soil tool 只读上下文。实际 soil UNAVAILABLE，页面指标 DEMO。

## 12. GIS

farm.center、farmlocation、location 和 AMap 提供基础位置；SpatialEvidenceRetriever 只读取调用方传入的 geometry/elevation/slope/ndvi。没有统一 polygon/CRS/version 或服务端 Field ownership。基础 GIS REAL/PARTIAL，AI 空间上下文 PARTIAL。

## 13. Satellite

SatelliteEvidenceRetriever 只消费 satellite Map；没有发现 Hikyun SDK 实际调用、卫星影像下载、NDVI 计算或栅格存储。Overview NDVI 使用常量 ndviPoints。卫星 UNAVAILABLE，NDVI 页面 DEMO。

## 14. Voice / ASR

前端 PromptInput 使用浏览器 SpeechRecognition/webkitSpeechRecognition；没有 Whisper、ASR 服务、音频上传、存储或转写持久化。浏览器语音 PARTIAL/EXTERNAL，服务端 ASR UNAVAILABLE。

## 15. Knowledge

KnowledgeBackendService 已有 sources/documents/versions/events CRUD；本机 MySQL source/document/version 各 1 行，类型 PACKAGED。KnowledgeEvidenceRetriever 加载 classpath orchard text，支持 lexical/BGE hybrid rerank；KnowledgeTool 可被 Agent 调用。当前是 packaged knowledge + V1 persistence，不能称作企业知识库。

## 16. Knowledge Upload / Ingestion

KnowledgeController 只有 JSON source/document/version；没有 MultipartFile、PDF/Word/图像解析、对象存储、chunk、embedding、Milvus knowledge collection 或 ingestion status。前端明确显示上传/index UNAVAILABLE。文本版本 REAL，文件 ingestion UNAVAILABLE，属于 P0。

## 17. MongoDB

MongoConfig、Case、CaseStorageServiceImpl 形成 legacy expert case 路径；容器可访问但 zhgy 为空。Mongo 基础设施 REAL，历史案例 EMPTY。后续必须决定 Mongo 是否是 HistoricalCase metadata 的唯一 owner，避免与 MySQL Candidate、Milvus row 多重事实源。

## 18. MySQL

MySQL localhost:3306/zhgy 只读成功，核心行数见第 1、6 节。MySQL 是当前 Commercial Control Plane 主库。V5_2_1 至 V5_2_6 表已存在，但正式 migration runner 仍是 release gate。farm/farmlocation/location/crops 需要 adapter 收敛。

## 19. Milvus

farmap_image_vectors_new 存在，request_id 主键，vector 512 维，IVF_FLAT/L2/nlist=128，rows=0。基础设施 REAL/READY，历史向量 READY_EMPTY；不能展示“命中历史案例”。

## 20. Historical data

Legacy Case 是 Mongo cases + Milvus image vector，当前为空；V2 Candidate 表存在但 0 行；HistoricalCaseIngestionService 只有 confirmed + execute 才写入，默认禁用。Candidate/Case/promotion 边界仍需统一，当前没有真实历史学习数据。

## 21. 模型

Hunyuan Vision（Python + Java direct）为外部依赖；Bailian/Qwen 为 Java ModelGateway 外部依赖；BGE/CLIP 为本地 packaged/cached；Whisper/ASR 和 Satellite model 未发现。当前 Python Local AI 与 Java legacy CLIP 两套 embedding 边界并行。

## 22. 当前 RAG 位置

Java DiagnosisRagService 编排 camera、weather、knowledge、historical_case、soil、spatial、satellite；EvidenceFusionService 融合；GroundedDiagnosisGenerator 通过 Bailian/Qwen 生成结构化 Diagnosis/Claims/Recommendations 并验证 evidence_ids；DiagnosisPersistenceService 写 agent_diagnoses。它是 Java 多模态 Evidence 原型，Python 视觉服务还未完全纳入统一 contract。

## 23. Agent 当前输入

PageContextAdapter/AgentContext 收集 farm、field、crop、phenology、location、cameraId、diagnosisId、recommendationId、imageUrls、weather、diagnosis、sourceModes、page。Tool 包括 field-context、weather、camera、gis、multimodal-rag、historical-case、knowledge、task。问题是前端事实未全部服务端重组，Overview/Diagnosis 可注入 Demo，soil/satellite 依赖 caller Map，Agent 本地默认 persistence=file。

## 24. Frontend 当前边界

真实入口：/diagnosis/analyze、/ai-model/upload、/api/agent/runs、/api/expert-reviews、/api/knowledge。Demo 入口：overview/adapter.ts 直接导入 demoFarm/demoFields/demoWeather/demoInsights/demoRecommendations；overview/index.tsx 固定 getDemoOverviewViewModel；a12-demo 使用 Demo fallback；aiContext 初始值来自 Demo；demoEvidence 含 camera/weather/soil/knowledge/historical。前端有状态意识，但首页尚未真实优先。

## 25. 重复实现

| 领域 | 旧 | 新 | 风险 |
|---|---|---|---|
| 视觉 | Java direct Hunyuan + Flask | DiagnosisRag + Qwen | 结果/身份分裂 |
| Image embedding | Java CLIP ONNX | Python Local AI CLIP | 模型/可用性不一致 |
| Historical | Mongo Case + Milvus | MySQL Candidate + gated ingestion | 双事实源 |
| Expert review | legacy endpoint | V2 endpoint | 兼容复杂 |
| Location | farmlocation | location/demo | 语义重复 |
| Knowledge | classpath text | MySQL V1 | ingestion 未接通 |

## 26. API 缺口

可复用：/user/get-farm、/map/farm/{farmId}/locations、/weather/intro、/weather/accumulated-temperature、/ai-model/upload、/diagnosis/analyze、/diagnoses/{diagnosisId}、/api/agent/runs、/api/expert-reviews、/api/historical-case-candidates、/api/knowledge、/api/runtime/health。

缺少：稳定 Field API、Field context API、Camera/capture event API、server-side weather/soil/satellite API、Python versioned Intelligence API、Knowledge multipart/ingestion/status API、统一 trace/correlation API。

## 27. 目标架构

Spring Boot 为 Commercial Control Plane：auth、ownership、Farm/Field context、Persistence、Diagnosis、Evidence、Agent、Approval、Operations、Expert Review、Audit。Python 为 Intelligence Plane：multimodal model、embedding、可选 ASR、structured output、health；不写业务库、不生成业务身份、不决定 candidate eligibility。MySQL 是业务事实；对象存储是原始图片/文档；Milvus 是版本化 vector；Mongo 只有在明确保留时使用。

## 28. Python Contract

Phase B 建议定义 ai.v1：请求含 contractVersion、server requestId/traceId、task、受控 image refs、text、field context、model policy；响应含 status、model metadata、observations、evidence(id/modality/sourceRef/confidence)、claims、uncertainty、latency。Python 不生成 diagnosisId/taskId/candidateId，Java 负责绑定、授权、持久化和 Evidence 校验。

## 29. Context Assembly

前端只提交意图和 resource IDs；Java 根据 JWT 校验 Farm/Field ownership，从 MySQL/外部 adapter 读取数据，生成带 sourceMode、observedAt、freshness、contentHash 的 context snapshot，再调用 Python。Agent 只读已绑定 context/evidence。

## 30. 失败语义

统一 UNAVAILABLE、EMPTY、STALE；禁止依赖失败返回默认成功、写入失败以 memory 宣称保存、外部天气/卫星失败静默显示 Demo、空历史库渲染为命中。建议错误码：AI_DEPENDENCY_UNAVAILABLE、FIELD_CONTEXT_UNAVAILABLE、EVIDENCE_SOURCE_EMPTY、KNOWLEDGE_INGESTION_FAILED。

## 31. 安全边界

关闭 Python 占位 key、任意图片 SSRF、前端伪造 ownership/provenance、浏览器模型 key、无 MIME/hash/大小隔离上传、日志泄露 key/URL token、生产默认密码/JWT secret。

## 32. MVP 差距矩阵

| 能力 | 当前 | 分类 | 优先级 |
|---|---|---|---|
| Farm 主数据 | MySQL | REAL | P1 |
| Field 主数据 | 点位/观测，缺 aggregate | PARTIAL | P0 |
| Camera registry | 无 | UNAVAILABLE | P1 |
| Image upload | Java | REAL | P1 |
| Python vision contract | legacy HTTP | PARTIAL | P0 |
| Python runtime health | 不统一 | PARTIAL | P0 |
| Soil/sensor | context/demo | UNAVAILABLE | P1 |
| Weather | 文字/积温，实时依赖 key | PARTIAL | P1 |
| GIS | farm/points | PARTIAL | P1 |
| Satellite/NDVI | context/demo | UNAVAILABLE | P1 |
| Voice/ASR | browser | PARTIAL | P2 |
| Knowledge retrieval | packaged + MySQL V1 | PARTIAL | P1 |
| Knowledge upload/index | 无 | UNAVAILABLE | P0 |
| Agent context | caller supplied | PARTIAL | P0 |
| Historical Candidate | 0 rows | EMPTY | P1 |
| Evidence grounding | Java | PARTIAL | P1 |
| Observability | 不完整 | UNAVAILABLE | P1 |

## 33. P0 问题

1. Python endpoint 没有版本化 Farm/Field/Evidence contract。
2. Java direct Hunyuan、Flask Hunyuan、Bailian/Qwen 并行。
3. 没有规范 Field identity；A-12 主要来自 Demo/请求输入。
4. Knowledge upload/index 缺失。
5. Overview 由 Demo ViewModel 驱动。
6. Mongo、Candidate、Milvus 历史为空。
7. 没有 Java+Python E2E runtime gate。
8. Legacy Python placeholder key/任意 URL 具有生产安全风险。

## 34. P1 问题

camera registry 缺失；weather 信任 caller 且存在随机历史数据；soil/satellite/spatial 是 context adapter；Mongo legacy 与 Candidate 生命周期未收敛；migration runner 未成为 release gate；Agent 默认 file persistence；location 表语义重复；旧新 diagnosis API 并行；外部 key readiness 不可验证；浏览器 SpeechRecognition 不是企业 ASR。

## 35. P2 问题

统一 contractVersion/traceId/sourceMode/freshness/contentHash；能力、成本、延迟、错误指标；统一 Farm/Field/Camera/Observation DTO；Milvus metadata/schema version；real/empty/unavailable/stale UI；脱敏日志、SLO、限流；论文数据与商业数据分离。

## 36. Phase B–G 路线

- Phase B Python Contract & Runtime：ai.v1、health、allow-list、SSRF 防护、统一 IntelligenceGateway、deprecated legacy endpoint。
- Phase C Farm/Field/Camera/Context：Field aggregate、camera/capture event、server-side weather/GIS/soil freshness、真实优先 UI。
- Phase D Knowledge Upload & Ingestion：multipart、解析、chunk、版本、embedding、index status。
- Phase E Multimodal RAG Wiring：Python observations 与 Java Evidence 统一、多模态 context、引用校验。
- Phase F Agent + Context：canonical context/evidence、bounded planner、approval、diagnosisId provenance。
- Phase G Commercial E2E & Evaluation：真实链路、grounding/evidence sufficiency/latency/cost/failure 指标。

## 37. Phase B 入口验收

Python health 返回版本/能力/依赖；/v1/multimodal/analyze 对齐 Java schema；图片 allow-list/MIME/大小/hash/timeout/SSRF 测试通过；Java 只经一个 IntelligenceGateway；Legacy endpoint 有弃用边界；依赖缺失不伪造成功；本报告和 JSON inventory 纳入 release review。

## 38. 论文可用结论

FarMap 可描述为：完成商业控制面和产品闭环基础、具备多模态 Evidence 编排与 Agent 操作闭环原型的农业 AI 决策平台；Python 视觉/embedding 服务和真实农业数据接入仍在契约收敛阶段。研究贡献包括 Evidence Fusion、Evidence Grounding、Tool Calling、Human-in-the-loop、Outcome supervision。Demo、packaged knowledge、evaluation JSON 和真实 MySQL 行必须分开统计。

## 39. 本阶段不做

不重构 Python；不改 Java/前端业务；不安装新基础设施；不创建或插入历史案例；不写 Milvus；不训练/微调；不把 Demo 提升为 REAL；不在 Phase A 实施 Phase B。

## 40. 证据文件

Python：zhgy/app.py、zhgy/hunyuan_vision.py、zhgy/requirements.txt、zhgy/Dockerfile、local-ai/app.py、local-ai/requirements.txt、scripts/run-local-ai.ps1、scripts/validate-milvus-runtime.py、scripts/ingest-historical-cases.py。

Java：application.yml、AIModelServiceImpl、LocalAiClient、DiagnosisRagService、VisualEvidenceRetriever、KnowledgeEvidenceRetriever、SoilEvidenceRetriever、SatelliteEvidenceRetriever、MongoConfig、MilvusConfig、CaseStorageServiceImpl、KnowledgeBackendService、AgentContext、AgentDiagnosisRequestFactory。

Frontend：features/overview/adapter.ts、views/overview/index.tsx、features/agent/PageContextAdapter.ts、features/agent/contract.ts、features/diagnosis/service.ts、views/diagnosis/Detail.tsx、features/knowledge-expert/adapter.ts、features/knowledge-expert/service.ts、views/expert/revision/PromptInput.tsx。

## 41. Release Gate

Phase A：GO。Commercial MVP：NO-GO。P0 关闭前不能宣称真实农场数据已经驱动 Python 多模态 RAG + Agent 全链路。进入 Phase B 前确认 Control Plane/Intelligence Plane 边界、Mongo 是否保留、首批真实输入范围和 contract-only 变更范围。

## 42. 最终判断

FarMap 已超过 Research Prototype，但还没有达到可直接交付的 Commercial Agricultural AI SaaS。最大优势是 Java 产品闭环和可审计 Evidence/Agent 基础；最大风险是现实数据边界、Python 集成契约、Field identity、知识 ingestion、历史数据为空以及首页 Demo 驱动。

**最终：Phase A Audit GO；Commercial MVP Integration Readiness NO-GO；下一步 Phase B — Python Contract & Runtime。**

