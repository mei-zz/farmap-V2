# FarMap 知识检索 Golden 评估 v1

## 数据与范围

- 数据集：`zhgy/zhgy/src/test/resources/knowledge/knowledge-golden-set-v1.json`
- 规模：50 条，覆盖脆李、柑橘、一季中稻、晚稻/再生稻
- 评估对象：作物/物候查询改写、结构化知识命中、知识键命中、跨作物串扰
- 结构化预期：39 条；其余问题保留给物候确定性回答或解释型 RAG

## 当前运行库结果（2026-09-11）

| 指标 | 结果 | 门槛 |
|---|---:|---:|
| 结构化命中率 | 1.0000 | 1.0000 |
| 期望知识键命中率 | 0.9487 | ≥ 0.9000 |
| 跨作物串扰率 | 0.0000 | 0.0000 |

## 执行方式

该测试默认跳过，避免普通离线测试依赖本机数据库；在已配置 MySQL 的环境中显式开启：

```powershell
$env:FARMAP_KNOWLEDGE_GOLDEN_EVAL = "true"
$env:SPRING_DATASOURCE_USERNAME = "<数据库用户>"
$env:SPRING_DATASOURCE_PASSWORD = "<数据库密码>"
mvn -q -Dtest=KnowledgeGoldenMysqlEvaluationTest test
```

每次修改检索或结构化规则后，应重新执行该测试；任何跨作物命中都必须保持为零。
