# FarMap 主回答模型池与模型配置抽离结果

## 结论

本次已完成：

- 纯文字回答任务接入可配置随机模型池；多模态、专家、图像理解、升级和 `force_*` 路径保持原选模逻辑。
- 模型相关配置已迁移到 [`farmap-models.yml`](../zhgy/zhgy/src/main/resources/farmap-models.yml)，`application.yml` 通过现有 `spring:` 根节点导入。
- 初始 52 个候选完成真实串行冒烟后，移除 7 个当前主回答最小请求不通过的模型，最终保留 45 个。
- 最终 45/45 冒烟成功，`permission_denied`、`model_not_found`、`timeout` 均为 0，模型池已开启 `enabled=true`。

## 变更文件

| 文件 | 变更 |
|---|---|
| [`AnswerPoolProperties.java`](../zhgy/zhgy/src/main/java/com/mei/zhgy/config/AnswerPoolProperties.java) | 新增 `farmap.ai.router.answer-pool` 配置绑定、任务类型校验、策略兜底、范围限制和模型清洗。 |
| [`AnswerModelPool.java`](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/AnswerModelPool.java) | 新增线程安全随机/轮询候选排序；使用现有 `ModelAvailabilityRegistry.status` 跳过已标记不可用模型。 |
| [`ModelRouter.java`](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/ModelRouter.java) | 仅在纯文字任务、无 `force_*`、无升级且池有有效候选时替换 requested model；同池接力后仍接原有 fallback。保留三参数兼容构造。 |
| [`BailianModelClient.java`](../zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/BailianModelClient.java) | 仅为冒烟 `probe` 支持固定中文最小请求和真实 `max_tokens=1`；普通调用预算逻辑不变。 |
| [`application.yml`](../zhgy/zhgy/src/main/resources/application.yml) | 在原有 `spring:` 下加入 `classpath:farmap-models.yml`，删除已迁移的重复模型配置。检索和业务配置保留。 |
| [`farmap-models.yml`](../zhgy/zhgy/src/main/resources/farmap-models.yml) | 新增模型配置、意图模型配置、embedding/rerank 配置、主回答池和 45 个最终候选。 |
| [`AnswerModelPoolTest.java`](../zhgy/zhgy/src/test/java/com/mei/zhgy/service/ai/AnswerModelPoolTest.java) | 新增随机、轮询、边界、不可用过滤和配置清洗测试。 |
| [`ModelRouterAnswerPoolTest.java`](../zhgy/zhgy/src/test/java/com/mei/zhgy/service/ai/ModelRouterAnswerPoolTest.java) | 新增同池接力顺序、禁用回退、强制/升级隔离测试。 |
| [`AnswerPoolSmokeTest.java`](../zhgy/zhgy/src/test/java/com/mei/zhgy/service/ai/AnswerPoolSmokeTest.java) | 新增显式开关触发的串行全池冒烟测试，不进入默认模型调用。 |
| [`answer-pool-smoke-result.md`](answer-pool-smoke-result.md) | 最终 45 个候选的逐模型连通性结果。 |

## answer-pool 配置

配置根路径：`farmap.ai.router.answer-pool`。所有字段均可由 YAML 中对应的环境变量覆盖。

| 字段 | 默认值 | 环境变量 | 含义 |
|---|---:|---|---|
| `enabled` | `true` | `FARMAP_ANSWER_POOL_ENABLED` | 总开关；设为 false 立即回到原 default-model 路由。 |
| `selection-strategy` | `random` | `FARMAP_ANSWER_POOL_SELECTION_STRATEGY` | `random` 或 `round_robin`，非法值自动为 `random`。 |
| `apply-task-types` | 5 个纯文字任务 | YAML 固定项 | `COPILOT`、`FIELD_SUMMARY`、`EVIDENCE_SYNTHESIS`、`GROUNDED_GENERATION`、`FUTURE_AGENT_TOOL_DECISION`。未知枚举启动时告警并忽略。 |
| `same-pool-fallback-n` | `3` | `FARMAP_ANSWER_POOL_SAME_POOL_FALLBACK_N` | 首选失败后最多尝试几个同池候选，限制在 0–8。 |
| `skip-unavailable` | `true` | `FARMAP_ANSWER_POOL_SKIP_UNAVAILABLE` | 跳过注册表中已标记 `unavailable` 的模型；未知状态仍可尝试。 |
| `models` | 最终 45 个 | `FARMAP_ANSWER_POOL_01`…编号占位 | 去空白、去重、保序后的候选清单。 |

最终保留的模型：

`glm-5`、`glm-5.1`、`glm-5.2`、`glm-4.7`、`qwen-max`、`qwen3-max-preview`、`qwen3-max-2025-09-23`、`qwen3-max`、`qwen3-max-2026-01-23`、`qwen3.6-max-preview`、`qwen3.7-max`、`qwen3.7-max-2026-05-20`、`qwen3.7-max-2026-06-08`、`qwen3.8-max-0902`、`qwen-plus`、`qwen-plus-latest`、`qwen-plus-0112`、`qwen-plus-1220`、`qwen-plus-2025-01-25`、`qwen-plus-2025-04-28`、`qwen-plus-2025-07-14`、`qwen-plus-2025-07-28`、`qwen-plus-2025-09-11`、`qwen-plus-2025-12-01`、`qwen3.5-plus`、`qwen3.5-plus-2026-02-15`、`qwen3.5-plus-2026-04-20`、`qwen3.6-plus`、`qwen3.6-plus-2026-04-02`、`qwen3.7-plus-2026-05-26`、`qwen3-235b-a22b`、`qwen3-235b-a22b-instruct-2507`、`qwen3.5-122b-a10b`、`qwen3.5-397b-a17b`、`qwen3.5-35b-a3b`、`qwen3.6-35b-a3b`、`qwen3-next-80b-a3b-instruct`、`deepseek-v3`、`deepseek-v3.1`、`deepseek-v3.2`、`deepseek-v3.2-exp`、`deepseek-v4-pro-0813`、`kimi-k2.5`、`kimi-k2.6`、`Moonshot-Kimi-K2-Instruct`。

## 选模与接力顺序

1. `COPILOT` 等 5 类纯文字任务，且没有 `force_fast/force_qwen36/force_vl32/force_vl235`、没有升级命中时，调用模型池。
2. `random` 从当前可用候选中选首选，再对剩余候选做一次 Fisher–Yates 顺序打乱；`round_robin` 使用原子游标环形选择。
3. `orderedCandidates()[0]` 作为 `requestedModel`。
4. 取同池第 1 个至最多第 `same-pool-fallback-n` 个作为优先接力。
5. 再拼接原有 `ModelRouter.fallbackModels` 跨档链路，整体去重、保序，并由既有 `ModelGateway` 的 `max-candidates` 限制最终尝试数。
6. 池关闭、任务不适用、池为空或全部被已知不可用状态过滤时，`requestedModel` 和 fallback 与原逻辑一致；`farmap.ai.router.default-model=qwen-plus-2025-07-28` 保留为原有默认/回退口径。

以下路径没有走主回答池：`MULTIMODAL_DIAGNOSIS`、`HARD_DIAGNOSIS`、`IMAGE_UNDERSTANDING`、`EXPERT_PRE_REVIEW`、任意 `force_*`、升级命中路径，以及 `ModelGateway` 候选执行循环。

## 配置迁移对照

已迁移到 `farmap-models.yml`，属性 key 和默认值保持不变：

- `bailian` 全段（API 占位、地址、区域、workspace、超时、重试）；
- `hunyuan` 全段；
- `farmap.agent.planner.model-enabled`；
- `farmap.knowledge.embedding.*`、`farmap.knowledge.rerank.*`；
- `farmap.ai.router.*` 原有路由、升级、intent 全套，加新增 `answer-pool.*`；
- 实际代码中的 `farmap.ai.generation.*`、`farmap.ai.local-vision.*`、`farmap.ai.legacy-java-clip.*`、`farmap.ai.local.*`；
- `model.api.*`。

保留在 `application.yml`：数据源、Mongo、MyBatis、JWT、Milvus、server、上传、COS、Open-Meteo、multipart、`farmap.copilot`、`presentation-data`、agent persistence/case-ingestion、knowledge storage/chunk/fusion/RRF/BM25 参数、`farmap.ai.service-token` 和 `farmap.ai.retrieval.*`。

导入方式是 Spring Boot 2.6 支持的强制 classpath import：

```yaml
spring:
  config:
    import:
      - classpath:farmap-models.yml
```

不使用 `optional:`，这样独立配置文件缺失会在启动时立即暴露，而不是静默丢失模型配置。

## 冒烟结果与测试

最终报告：[`docs/answer-pool-smoke-result.md`](answer-pool-smoke-result.md)

- 初始 52 个模型真实串行冒烟：45 个成功；`qwen3.8-24t-a95b` 明确 `model_not_found`；另有 6 个当前最小非流式/响应契约不通过。
- 从池中摘除：`qwen3.8-24t-a95b`（不存在）、`glm-4.5`（仅支持流式）、`glm-4.6`（无可展示响应）、`qwen3.7-max-preview`、`qwen3.7-max-2026-05-17`、`MiniMax-M2.1`、`MiniMax-M2.5`（当前最小请求要求开启 thinking）。这 7 个均未留在最终主回答池。
- 最终 YAML 保留 45 个模型后再次串行冒烟：**45/45 成功**。
- 最终报告：`permission_denied=0`、`model_not_found=0`、`timeout=0`、其他错误=0。
- `mvn -q -DskipTests compile`：通过。
- `AnswerModelPoolTest,ModelRouterAnswerPoolTest`：通过（8 tests）。
- `ModelRouterTest`：通过（3 tests）。
- `ModelGatewayTest`：已有测试失败，原因是该测试期望专家首选 235B，而当前既有 `ModelRoutingPolicy` 默认专家模型是 32B；本次没有改动多模态/专家选模逻辑，也没有修改该已有测试。
- 显式冒烟测试：通过；使用项目 `.env` 中已有运行配置，但报告和日志没有写入密钥或响应正文。

## 回滚

1. 立即回滚池：设置 `FARMAP_ANSWER_POOL_ENABLED=false`，重启后回到原 `default-model` 和原 fallback，其余配置不变。
2. 彻底撤回独立文件：把 [`farmap-models.yml`](../zhgy/zhgy/src/main/resources/farmap-models.yml) 内容按原层级并回 [`application.yml`](../zhgy/zhgy/src/main/resources/application.yml)，再删除 `spring.config.import` 的三行；确保每个 key 只保留一份。

## 未改动项

未改动意图识别包、知识 RRF/BM25/Milvus、参考图/多模态链路、专家和图像理解选模、四种 `force_*` 语义、ModelGateway 候选循环、前端、数据库、鉴权、写操作审批；未输出任何真实密钥。
