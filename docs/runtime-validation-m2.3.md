# Milestone 2.3 Runtime Validation

验证日期：2026-09-05（Asia/Shanghai）

## Runtime results

| Component | Status | Observed result |
| --- | --- | --- |
| Git preflight | PASS | `feature/multimodal-rag` 与 origin 同步；开始时工作区干净；`.env` ignored |
| Docker Engine | VERIFIED | Docker Desktop 4.40.0；Engine 28.0.4，linux/amd64 |
| etcd | VERIFIED | v3.5.14，running |
| MinIO | VERIFIED | RELEASE.2024-05-10，running |
| Milvus | VERIFIED | v2.4.0，`127.0.0.1:19530`，read-only list collections 成功 |
| Historical collection | BLOCKED | `farmap_image_vectors_new` 不存在，rows=0；`HISTORICAL_VECTOR_DATA_EMPTY` |
| Historical metadata | BLOCKED | 旧链路为 Milvus `request_id` → MongoDB `cases.request_id`；无向量集合，未伪造检索结果 |
| Local AI | VERIFIED | FastAPI `127.0.0.1:8001`，启动时 load + warmup |
| BGE | VERIFIED | `BAAI/bge-small-zh-v1.5`，LOCAL，512-d，normalized；独立验证 28 ms（2 texts） |
| CLIP | VERIFIED | `clip-image-encoder.onnx`，LOCAL，512-d，normalized；A-12 图像独立验证 360 ms |
| Java → Local AI | VERIFIED | 真实 Java integration test 同时通过 text/image endpoints |
| Knowledge | VERIFIED | lexical candidates → BGE batch embedding → cosine → configurable hybrid rank → top 3；内容 hash/model/version cache |
| Weather | VERIFIED | temporal query；A-12 输入为产品演示上下文 |
| Soil | DEMO | 页面 84% 为演示数据，未作为 REAL sensor evidence 注入本次 E2E |
| Spatial/GIS | DEMO | 现有页面上下文可展示；未将演示值标成 REAL evidence |
| Evidence fusion | VERIFIED | 5 input/output evidence；记录 rank、fused score、source reliability 和 latency |
| Qwen router | VERIFIED | 高风险策略从 VL32 升级至 `qwen3-vl-235b-a22b-thinking` |
| A-12 E2E | PASS | 5 evidence、2 claims、2 recommendations；claim/recommendation evidence IDs 全部合法 |
| Frontend build | PASS | `pnpm build` |
| Backend tests | PASS | 18 normal tests passed、2 opt-in runtime tests skipped in the normal run；Local AI 与 A-12 opt-in runtime tests separately PASS |
| Python validation | PASS | health、BGE batch 512-d、CLIP 512-d |

## A-12 result

- Field/crop/stage: A-12 / 柑橘 / 果实膨大期
- Input image: product demo camera asset (`DEMO` input, real CLIP execution)
- Rainfall: 58 mm in 14 days (`DEMO` structured input)
- BGE invoked: yes, LOCAL, 512-d
- CLIP invoked: yes, LOCAL, 512-d
- Milvus invoked: yes, read-only runtime validation; collection unavailable
- Historical evidence: 0, `BLOCKED`
- Evidence: 5
- Selected Qwen: `qwen3-vl-235b-a22b-thinking` (policy escalation)
- Claims: 2
- Recommendations: 2
- Evidence reference validation: PASS
- Total latency: 136186 ms
- Tokens: input 3103; output 1543; thinking 1155

## Runtime truth table

| Source/runtime | Truth |
| --- | --- |
| Camera input | DEMO |
| Knowledge corpus | LOCAL |
| Weather input | DEMO |
| Historical retrieval | BLOCKED |
| Soil | DEMO |
| Spatial | DEMO |
| BGE execution | LOCAL |
| CLIP execution | LOCAL |
| Milvus service | LOCAL |
| Qwen | API |

The absence of historical vector data is the only retrieval limitation. Camera, knowledge, weather, evidence fusion, model routing, and grounded reference validation continue to operate.
