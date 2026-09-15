# FarMap Knowledge & Expert Contract V1

本文件定义 Knowledge、Expert Review、Historical Candidate 的目标 contract。当前页面已按真实边界产品化；本文件不授权立即修改 Backend。

## KnowledgeSource

```text
sourceId
publisher
authorityLevel
region
cropScope
sourceType
status
createdAt
updatedAt
```

## KnowledgeDocument / Version

```text
documentId
sourceId
title
category
crop
region
contentLocation
currentVersion
status

documentId
version
contentHash
effectiveDate
updatedAt
indexStatus: PENDING|INDEXING|INDEXED|FAILED|RETIRED
chunkCount
embeddingModel
```

Evidence 需要引用 `documentId/version/chunkId`。当前 classpath `orchard_management.txt`、chunkId、contentHash 只能作为内部 Retriever 事实，不能被页面当作文档管理 API。

## ExpertReview Current Contract

```text
GET  /api/expert-review
GET  /api/expert-review/{diagnosisId}
POST /api/expert-review/{diagnosisId}
```

字段：`diagnosisId/status/reviewer/reviewedAt/aiDiagnosis/expertDiagnosis/evidence/recommendations/before/after`。服务端以 diagnosisId 为主键 upsert，decision 会归一为 `IN_REVIEW/CONFIRMED/CORRECTED`。

## ExpertReview Target V1

```text
reviewId
diagnosisId
farmId
fieldId
reviewerId
status: PENDING|IN_REVIEW|CONFIRMED|CORRECTED|REJECTED
decision: CONFIRM|CORRECT
aiDiagnosisSnapshot
expertDiagnosis
comment
evidenceIds
recommendationIds
before
after
version
createdAt
reviewedAt
```

Target API：

```text
GET  /api/expert-reviews?page=&status=&farmId=&fieldId=&reviewerId=
GET  /api/expert-reviews/{reviewId}
GET  /api/expert-reviews/{reviewId}/history
POST /api/expert-reviews/{reviewId}/submit
```

提交必须带 version。版本冲突返回 `VERSION_CONFLICT`，不能静默覆盖另一位专家的修改。

## HistoricalCaseCandidate

```text
candidateId
diagnosisId
reviewId
farmId
fieldId
crop
imageAssetIds
expertDiagnosis
outcome
evidenceIds
qualityStatus: PENDING|READY|REJECTED|INGESTED
createdAt
createdBy
version
```

Candidate 不等于 HistoricalCase。只有 `CONFIRMED` Review、必需图像、专家诊断和 outcome 完整，并通过质量检查后，才允许触发 ingest。

## Truth Boundary

- Demo：不读写真实 Review；历史案例标为“历史案例（演示）”。
- Real：只显示 API 返回的 Review；空集合是 EMPTY，不生成案例。
- Historical：当前底层 `READY_EMPTY / rowCount=0`，不能声称已有 Historical Case Learning。
- Knowledge：当前可用于 Diagnosis/Agent Evidence，但没有文档浏览、上传、分类、索引 API。
