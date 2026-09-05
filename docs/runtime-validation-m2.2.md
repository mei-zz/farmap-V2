# FarMap Milestone 2.2 Runtime Validation

验证日期：2026-09-05  
分支：`feature/multimodal-rag`  
范围：真实百炼模型、真实多模态诊断链路、本地 Embedding、检索可用性与安全边界。  
明确不包含：Agent Runtime、完整 RAG 基础设施启动、数据库迁移。

## A. Git、环境与 Secret Preflight

- 根仓库当前分支为 `feature/multimodal-rag`，GitHub 目标 remote 为 `origin`。
- 后端已从嵌套 gitlink 合并为根仓库普通源码；当前 `git ls-files -s` 未发现 `160000` gitlink。
- `.env` 被 `.gitignore` 忽略，tracked 文件中未发现环境文件、私钥或常见 API Key pattern。
- 本地 `.env` 只做布尔状态检查：API Key=`SET`，Base URL=`SET`。实际值未输出、未写入代码、未写入文档。
- 兼容现有 `APIKEY` / `BASEURL`，启动脚本仅在当前进程映射到 `BAILIAN_API_KEY` / `BAILIAN_API_BASE_URL`，不会改写 `.env`。

## B. Bailian Model Availability

探测使用一个最小请求逐模型执行一次，且不启用结构化输出，避免将供应商对 `response_format` 的差异误判为模型不可用。结果如下：

| Model | HTTP | Status | Probe latency | Usage |
| --- | ---: | --- | ---: | --- |
| `qwen3.6-plus` | 200 | `VERIFIED` | 4406 ms | input 17 / output 191 / thinking 181 / total 208 |
| `qwen3-vl-32b-thinking` | 200 | `VERIFIED` | 2100 ms | input 17 / output 87 / thinking 3 / total 104 |
| `qwen3-vl-235b-a22b-thinking` | 200 | `VERIFIED` | 4072 ms | input 17 / output 214 / thinking 184 / total 231 |

代码和脚本支持的状态分类为：`VERIFIED`、`UNAVAILABLE`、`PERMISSION_DENIED`、`MODEL_NOT_FOUND`、`AUTH_FAILED`、`NETWORK_ERROR`。探测不会在应用启动时自动消耗配额；后端通过 `/ai-model/availability/probe` 显式触发。

## C. Model Router 与真实调用

固定模型映射：

| Task | Primary model | Fallback / escalation |
| --- | --- | --- |
| Evidence Synthesis、Grounded Generation、Copilot | `qwen3.6-plus` | 有限 fallback |
| Multimodal Diagnosis | `qwen3-vl-32b-thinking` | 低置信度、冲突、高风险或复杂输入时升级 |
| Hard Diagnosis / Expert Pre-review | `qwen3-vl-235b-a22b-thinking` | 有限 fallback |

真实 redacted validation 已完成：

- qwen3.6-plus 普通文本调用：HTTP 200，finish reason=`stop`。
- qwen3.6-plus grounded JSON：HTTP 200，返回 `diagnosis`、`claims`、`recommendations` 等结构，3 条 Claim，Evidence ID 全部有效。
- qwen3-vl-32b-thinking：使用农业叶片图像和 A-12 上下文完成真实多模态结构化调用，Evidence ID 全部有效。
- qwen3-vl-235b-a22b-thinking：使用一次 hard/escalation fixture 完成真实结构化调用，Evidence ID 全部有效。
- 所有 HTTP 重试都有上限；默认最多 1 次重试，配置上限为 2 次，没有无限重试。

## D. Local AI Runtime

运行环境按用户要求使用 `llm` 环境的等价解释器：`D:\anaconda\envs\llm\python.exe`。当前 Codex PowerShell 未加载 Conda shell hook，因此使用该环境的直接解释器路径，不创建新环境、不下载模型。

| Asset | Result | Notes |
| --- | --- | --- |
| Existing Java CLIP ONNX | `VERIFIED` | 独立本地测试已用 2 张农业 Demo 图像产生 3 个 512 维向量（含同图重复），有限值且确定性一致。当前完整 Maven JVM 因 native memory 不足受控跳过；不改变业务 fallback。 |
| BGE-small-zh-v1.5 | `VERIFIED LOCAL` | 仅使用现有 Hugging Face cache，offline-only；512 维、mean pooling、L2 normalized、finite、cosine self=1.0。 |
| Java text provider | `UNUSED / INCOMPLETE` | 当前没有把 Python BGE 适配器伪装成 Java 生产能力；`LocalVisionService.embedText` 保持空结果并明确标注。 |
| DINOv2 / BERT candidates | `DEFERRED` | 只保留资产清单，不复制、不接入生产路径。 |

## E. Vector Infrastructure

- 已对 `localhost:19530` 做只读连通性检查，结果不可达。
- Docker client 存在，但 Docker daemon 不可用；没有启动容器、创建集合、删除数据、重建索引或修改 schema。
- 现有 Milvus 代码和集合配置保持不变：`farmap_image_vectors_new`、512 维、既有 IVF_FLAT/L2 配置。
- 本轮状态：`MILVUS_RUNTIME_BLOCKED`。
- 因 Milvus 与历史元数据存储无法在当前环境完成真实查询，Historical Case Retriever 不会伪造 B-07/C-03/A-08 等案例；只有真实存储可用或请求显式携带真实历史上下文时才返回历史证据。

## F. Retriever Provenance

| Modality | Runtime status | `sourceMode` / rule |
| --- | --- | --- |
| Camera | `REAL_INPUT` | `real-input`；使用请求实际图像 URL，并作为 VL 多模态消息发送 |
| Weather | `REAL_STRUCTURED_CONTEXT` | `structured-context`；执行 14 天时间窗口数据处理，不生成外部天气数据 |
| Knowledge | `REAL_LOCAL_RETRIEVAL` | `classpath-retrieval`；当前为现有农业知识文件的段落级关键词召回，不冒充向量语义检索 |
| Historical Case | `BLOCKED` | 需要真实 CLIP + Milvus + 历史元数据，当前不可用 |
| Soil | `UNAVAILABLE` | 仅在请求提供真实土壤传感器上下文时启用 |
| Spatial / Satellite | `UNAVAILABLE` | 仅在请求提供真实 GIS/卫星上下文时启用 |

## G. A-12 Real Multimodal RAG E2E

opt-in 测试：`DiagnosisRagIntegrationTest`。测试使用真实 A-12 图像、A-12/柑橘/果实膨大期上下文、14 天累计降雨 58 mm 和现有农业知识资源，走生产默认的 Evidence Synthesis + Multimodal Diagnosis 路径。

最近一次完整 Java E2E 结果：

- status：`PASS`
- selected model：`qwen3-vl-32b-thinking`
- returned Evidence：3（camera、weather、knowledge）
- Claims：3
- Recommendations：3
- total latency：77175 ms
- selected call usage：input 2947 / output 1124 / thinking 592
- 所有 Claim 与 Recommendation 的 Evidence ID 均存在于服务端检索 Evidence 集合。
- 服务端负责回填检索 Evidence，不信任模型自行返回的 Evidence 列表；模型只能引用已校验的 Evidence ID。

独立 redacted Python 验证同时确认了 qwen3.6-plus grounded JSON、VL32 多模态 JSON 和 VL235 hard fixture 的真实返回结构；脚本从不打印 response body、thinking content、图像或密钥。

## H. 安全与可审计边界

- 日志和 `ModelUsageRecord` 仅保留 model、task、route、status、latency、token usage、Evidence ID 和 error type；不保存模型正文、隐藏推理或图像 Base64。
- `GroundedDiagnosisGenerator` 会移除 `<think>` 内容，并只接受可解析结构化 JSON。
- 结构化结果的 unknown field 兼容和 alternatives 字符串归一化不放宽 Evidence 校验；Claim/Recommendation 缺失或引用未知 ID 仍会失败并有限回退。
- RestTemplate 日志已提升到 WARN，避免调试日志打印多模态请求体。
- JWT 拦截器只记录 token 的 `ABSENT/PRESENT` 状态，不记录实际 token。
- `RAG_GENERATION_MODE` 仍由环境变量控制；本地启动脚本默认 `qwen`，可显式使用 `-GenerationMode template`。没有认证 bypass、默认账号密码或硬编码 Key。
- 本轮没有启动 Agent Runtime。

## I. Build and Test Results

- `pnpm build`：`PASS`；现有 Vite chunk size warning 保留，未引入新依赖。
- `mvn test`：`PASS`；12 tests，0 failures，0 errors，2 controlled skips（真实集成测试需显式环境变量；CLIP native runtime 在本机完整 JVM 内存不足时跳过）。
- `FARMAP_RUN_REAL_INTEGRATION=true mvn -Dtest=DiagnosisRagIntegrationTest test`：`PASS`；真实默认 RAG E2E。
- `python scripts/validate-local-embeddings.py`：`PASS`；使用 `D:\anaconda\envs\llm\python.exe`，offline-only。
- `git diff --check`：`PASS`。
- tracked secret pattern scan：`0` hits。
- gitlink scan：`0` entries。

## J. Milestone Decision

**NOT READY FOR MILESTONE 3。**

本轮真实模型和 A-12 多模态 RAG 主链路已经可验证，但仍有三个明确边界：

1. Milvus/Docker daemon 不可用，历史案例向量检索尚未得到真实运行证明。
2. BGE-small-zh 已在 `llm` 环境离线验证，但尚未接入 Java 生产 Text Embedding Provider；知识检索仍是本地段落召回。
3. Agent Runtime 尚未启动，也未执行工具调用、任务执行或真实设备控制。

下一步应先恢复 Milvus/历史元数据服务并决定 BGE 的进程边界（Java ONNX、Python sidecar 或独立 embedding service），完成真实历史检索和文本向量 provider 验证后，再单独进入 Milestone 3 Agent Runtime。
