# FarMap 混合意图影子运行可观测性结果

## 变更范围

本次只增加影子运行的只读观测能力，没有改变意图路由、接管判定、提示词、模型池、超时、缓存、检索、回答模型池、参考图、前端、JWT 拦截器或业务鉴权。

变更文件：

- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/intent/LlmIntentClassifier.java`
  - 增加有界、线程安全的最近影子样本队列；样本只保留 SHA-256 前 16 位哈希及意图元数据。
  - 增加 `recentSamples(limit, mismatchOnly)` 与 `resetMetrics()`。
  - 在原有统计收口记录样本，并补充最近样本容量/数量、升级率、回退率。
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/copilot/intent/IntentModelProperties.java`
  - 增加 `recentSampleCapacity`，默认 200，范围限制为 0–1000。
- `zhgy/zhgy/src/main/resources/farmap-models.yml`
  - 增加 `FARMAP_INTENT_RECENT_SAMPLE_CAPACITY` 配置项，默认 200。
- `zhgy/zhgy/src/main/java/com/mei/zhgy/controller/IntentObservabilityController.java`
  - 新增三个 `/ai-model/intent/**` 诊断端点；不触发模型调用。
- `zhgy/zhgy/src/test/java/com/mei/zhgy/service/copilot/intent/LlmIntentClassifierObservabilityTest.java`
  - 增加脱敏、有界、比率、重置、LRU 保留和并发混合调用测试。

`/ai-model/**` 已由既有 JWT 拦截器保护，本次未修改 `WebMvcConfiguration`。

## 端点示例

### GET `/ai-model/intent/metrics`

返回结构示例（数值为示例）：

```json
{
  "code": 1,
  "data": {
    "mode": {
      "enabled": true,
      "shadowMode": true,
      "routingMode": "primary",
      "takeoverThreshold": 0.7,
      "escalateThreshold": 0.55,
      "totalTimeoutMs": 1800,
      "poolSizes": {
        "primary": 1,
        "flash": 12,
        "strong": 7
      }
    },
    "metrics": {
      "primaryCalls": 0,
      "strongEscalations": 0,
      "fallbackCalls": 0,
      "cacheHits": 0,
      "shadowCalls": 0,
      "ruleComparisons": 0,
      "ruleMismatches": 0,
      "ruleMismatchRate": 0.0,
      "strongEscalationRate": 0.0,
      "fallbackRate": 0.0,
      "recentSampleCapacity": 200,
      "recentSampleSize": 0,
      "intentDistribution": {},
      "latencyP50Ms": 0,
      "latencyP95Ms": 0,
      "modelCalls": {},
      "modelFailures": {}
    }
  }
}
```

### GET `/ai-model/intent/samples?limit=50&mismatchOnly=false`

样本按新到旧返回，且响应没有用户原文字段：

```json
{
  "code": 1,
  "data": [
    {
      "ts": 1789382400000,
      "messageHash": "0123456789abcdef",
      "ruleIntent": "GENERAL_QA",
      "llmIntent": "KNOWLEDGE_QUERY",
      "match": false,
      "tier": "PRIMARY",
      "model": "configured-model",
      "confidence": 0.91,
      "latencyMs": 326,
      "fallbackReason": null,
      "modeAtRecord": "shadow"
    }
  ]
}
```

`limit <= 0` 使用默认 50，最大返回 200 条；`mismatchOnly=true` 只保留 `match=false` 的样本。

### POST `/ai-model/intent/metrics/reset`

```json
{
  "code": 1,
  "data": {
    "reset": true
  }
}
```

该操作只清理观测计数、聚合、延迟样本和最近样本，不清理 LRU 结果缓存。

## 配置

```yaml
farmap:
  ai:
    router:
      intent:
        recent-sample-capacity: ${FARMAP_INTENT_RECENT_SAMPLE_CAPACITY:200}
```

shadow 默认仍为 `true`。影子模式切换仍只通过 `FARMAP_INTENT_SHADOW_MODE=false` 配置并重启完成，没有增加运行时热切换接口。

## 编译与测试

由于本机不存在任务文本指定的 `C:\dev\apache-maven-3.9.6\bin\mvn.cmd`，实际使用已安装的等价 Maven：
`D:\apache-maven-3.9.6\bin\mvn.cmd`。

- `-DskipTests compile`：通过。
- `LlmIntentClassifierObservabilityTest,AnswerModelPoolTest,ModelRouterAnswerPoolTest,ModelRouterTest`：通过。
- 既有 `LlmIntentClassifierTest,IntentModelPoolTest,IntentShadowSampleTest`：通过。
- `git diff --check`：通过。
- 已知历史 `ModelGatewayTest#fallsBackFromExpertToReasoner` 未纳入本次回归，且没有修改；它与本任务无关。

新增测试覆盖：

- 样本超过容量时只保留最近 N 条；
- 新到旧顺序与 `mismatchOnly` 过滤；
- 样本没有 `message`/`rawMessage` 字段，哈希格式为 16 位十六进制；
- `ruleMismatchRate`、`strongEscalationRate`、`fallbackRate`；
- reset 后计数和样本归零，LRU 结果仍可命中；
- 4 线程并发 classify、读取样本和 reset 不越界、不出现负数。

## 手测结果

- 已重启后端并加载当前代码；`GET http://127.0.0.1:8080/api/runtime/health` 返回 HTTP 200。
- 未携带登录态访问 `GET /ai-model/intent/metrics` 与 `POST /ai-model/intent/metrics/reset` 均返回 HTTP 401，新路径继续受既有 `/ai-model/**` JWT 拦截保护。
- 已重启前端开发服务；`GET http://127.0.0.1:5173/farmap/src/views/login/index.tsx` 返回 HTTP 200，之前因依赖未被当前 Vite 进程加载导致的动态导入错误已排除。
- 在不读取浏览器令牌、不改鉴权的前提下，无法从终端代替已登录网页完成三条接口的 authenticated HTTP 手测。请在当前网页登录态下访问三个端点完成基线、样本和 reset 验证。
- 本次没有产生模型调用、检索调用或数据写入。

## 边界确认

- 未修改路由判定与 shadow 接管逻辑。
- shadow 默认仍为 `true`。
- 未保存或返回用户原文，只保存短 SHA-256 哈希。
- 未返回 API Key、BaseURL 或模型正文。
- 未修改检索链路、RRF/BM25、回答模型池、多模态参考图和前端。
- Controller 不触发大模型调用。

## 回滚

删除新增 Controller、测试文件，并删除 `IntentModelProperties` 的容量字段、YAML 配置行，以及 `LlmIntentClassifier` 中的样本/派生比率/reset 增量即可；无需数据迁移，也不影响既有业务数据和 LRU 缓存。
