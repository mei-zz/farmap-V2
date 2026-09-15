# FarMap 知识检索阶段 A 交付报告

日期：2026-09-14

## 结论

已完成 Qwen 知识检索的两路融合改造：默认从旧的线性加权切换为 RRF，并增加按 actorId 隔离、按语料指纹自动重建的 BM25 索引缓存。没有改动 Milvus 集合、向量数据、结构化知识规则、gte-rerank-v2、意图层、参考图、前端或调用方接口。

## 改动文件与职责

- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/KnowledgeBm25.java`
  - 新增不可变 `Bm25Corpus`，保存分词结果、document frequency、文档数和平均长度。
  - 新增 `scoreAgainst` / `scoreAgainstTokens`，在同一语料快照上重复评分不再重复计算全库统计量。
  - 保留 `rank(List<Document>, String, int)`，固定小语料回归确认分数和顺序兼容旧实现。
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/knowledge/QwenKnowledgeVectorStore.java`
  - dense 召回与 BM25 召回分别排序、编号，再按 `fusion-mode` 选择 RRF 或旧 weighted。
  - `candidateTopN=20` 仍是送精排的粗排池上限；dense/BM25 两路召回深度独立默认为 30；精排上限仍为 5。
  - BM25 索引按 actorId 缓存；fingerprint SQL 只读取 `chunk_id, content_hash`，并沿用原有 owner、ACTIVE、READY、当前版本和集合条件。
  - 缓存容量有上限，语料指纹变化自动重建；fingerprint SQL 失败时直接全量构建，异常回退到 dense。
  - `KnowledgeVectorHit` 新增 `denseRank`、`bm25Rank`、`rrfScore`，原有分数字段保留。
- `zhgy/zhgy/src/main/resources/application.yml`
  - 新增 RRF、两路召回深度、BM25 缓存容量及 min-score 的环境变量配置。
- 测试文件
  - `KnowledgeBm25Test`：旧分数兼容、缓存命中/指纹变化/actor 隔离。
  - `QwenKnowledgeVectorStoreTest`：RRF 公式、单路/双路命中、权重、稳定平局、weighted 回滚。
  - `QwenKnowledgeRrfGoldenEvaluationTest`：复用现有 50 条 golden，真实服务可用时输出两模式逐条 top5、Recall@5、MRR、nDCG@5、串扰率、平均/P95。

## RRF 默认参数

对于 dense 排名 `denseRank` 和 BM25 排名 `bm25Rank`：

```text
rrfScore = denseWeight / (rrfK + denseRank)
         + bm25Weight / (rrfK + bm25Rank)
```

未命中一路贡献 0。默认值为 `rrfK=60`、`denseWeight=1.0`、`bm25Weight=1.0`。同分依次按双路命中、向量分、chunkId 字典序稳定打破。

## 配置与回滚

默认配置：

```yaml
farmap:
  knowledge:
    fusion-mode: ${FARMAP_KNOWLEDGE_FUSION_MODE:rrf}
    rrf-k: ${FARMAP_KNOWLEDGE_RRF_K:60}
    dense-weight: ${FARMAP_KNOWLEDGE_DENSE_WEIGHT:1.0}
    bm25-weight: ${FARMAP_KNOWLEDGE_BM25_WEIGHT:1.0}
    dense-candidate-n: ${FARMAP_KNOWLEDGE_DENSE_CANDIDATE_N:30}
    lexical-candidate-n: ${FARMAP_KNOWLEDGE_LEXICAL_CANDIDATE_N:30}
    bm25-cache-capacity: ${FARMAP_KNOWLEDGE_BM25_CACHE_CAPACITY:8}
```

回滚到旧线性加权：

```powershell
$env:FARMAP_KNOWLEDGE_FUSION_MODE = "weighted"
```

## Golden 与 A/B 结果

已定位并复用：`zhgy/zhgy/src/test/resources/knowledge/knowledge-golden-set-v1.json`，共 50 条；覆盖矩阵为 `docs/knowledge-coverage-matrix-v1.json`。仓库已有的结构化知识基线是结构化命中率 1.0000、知识键命中率 0.9487、跨作物串扰率 0.0000。

本机当前未开启 `FARMAP_KNOWLEDGE_RRF_GOLDEN_EVAL=true`，且未具备可确认的实时 MySQL/Milvus/Bailian 三件套，因此本轮没有伪造 weighted/RRF 的线上指标。对比测试已就位，运行时会对同一批 50 条逐条执行两种模式并打印：

| 指标 | weighted | rrf |
|---|---:|---:|
| Recall@5 | 待真实服务运行 | 待真实服务运行 |
| MRR | 待真实服务运行 | 待真实服务运行 |
| nDCG@5 | 待真实服务运行 | 待真实服务运行 |
| 跨作物串扰率 | 待真实服务运行 | 待真实服务运行 |
| 平均延迟 | 待真实服务运行 | 待真实服务运行 |
| P95 延迟 | 待真实服务运行 | 待真实服务运行 |

运行方式：

```powershell
$env:FARMAP_KNOWLEDGE_RRF_GOLDEN_EVAL = "true"
mvn -Dtest=QwenKnowledgeRrfGoldenEvaluationTest test
```

测试会对 Recall@5、MRR、nDCG@5 做 RRF 不低于 weighted 的断言，并对两种模式都断言跨作物串扰率为 0。

## 索引缓存可追溯性

每个 `QwenKnowledgeVectorStore` 暴露 `getBm25IndexHitCount()`、`getBm25IndexRebuildCount()` 和 `getBm25IndexCacheSize()`，同时记录：

```text
BM25 index cache hit scope=...
BM25 index rebuilt scope=... documents=... cacheSize=...
```

普通请求只执行轻量 fingerprint SQL；同一 actorId 且指纹不变时复用 `Bm25Corpus`，指纹变化或 actorId 不同则不会跨用户复用。

## 测试结果

- 主模块编译：通过。
- 新增/基础检索单测：7/7 通过。
- golden 对比测试：默认跳过，需显式开启真实服务环境。
- 知识 golden 文件结构测试、KnowledgeEvidenceRetriever、HybridKnowledgeRetriever：通过。
- 意图相关回归：通过。
- 同批次另有一个既有视觉测试失败：`CommercialMultisourceRagEvaluationTest.visionUnavailableIsZeroEvidenceAndReadyVisionUsesAiV1`，失败在视觉证据断言，未修改本任务范围外文件。

## 未改动项

Milvus 集合 `farmap_knowledge_chunks_qwen3_v1` 的 schema、索引和数据；结构化农业知识规则及短路逻辑；gte-rerank-v2；`KnowledgeEvidenceRetriever`、`KnowledgeSearchController` 调用方；意图识别层；多模态参考图；前端；owner 鉴权与作物隔离逻辑。
