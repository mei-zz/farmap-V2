# FarMap V2 Current State & Handoff

**Audit date:** 2026-09-08  
**Decision:** `HANDOFF_READY`  
**Scope:** targeted, read-only state audit and handoff freeze. No feature implementation, database write, data re-import, service restart, Milvus rebuild, environment reinstall, branch, commit, or push was performed.  
**Evidence authority:** current read-only MySQL/runtime observations take precedence for mutable counts; frozen result documents remain the authority for historical phase decisions and their original test baselines.

## 1. Executive Summary

FarMap Commercial MVP V1 remains **FROZEN / NO REGRESSION**. Core Pilot remains **PILOT CANDIDATE GO**; Production Rollout remains **CONDITIONAL**. AI Engine Phases 1.1–1.3 are GO/FROZEN, 1.4–1.6 are Core GO, and Phase 1.7 is **NOT STARTED**.

The REAL local product loop is usable with `userId=1`, `username=guest`, `mode=REAL`, and authorized farms `[1,8]`. Farm 7 belongs to another owner and correctly returns `403 FARM_ACCESS_DENIED`; no cross-user farm leakage was found. Manual Import V2 and Verify V2 are GO, with 1,497 canonical map points and all recorded integrity counters at zero.

The central new finding is not a failed map migration. The legacy source contains more agricultural data than the current Overview visibly consumes: 24 monthly weather prose rows, 582 accumulated-temperature observations, 144 guidance rows, crop metrics, model references, and map observations. Most of these rows already exist in the current `zhgy` database, but they do not all have a trusted commercial contract, actor-safe API, frontend integration, or UI presentation. Specifically, the weather rows are seasonal agronomic descriptions—not timestamped observed or forecast weather—so `FarmFieldService` deliberately reports `READY_EMPTY`. Health aggregate and actor-scoped diagnosis-by-farm listing are not implemented. The source contains no stable field/plot/block/parcel or sensor model, so 大足 `farm_fields=0` is not evidence of a missed field migration and must not be filled with fake fields.

Production knowledge is healthy: `farmap_knowledge_chunks_qwen3_v1=507`. The reported zero belongs to `farmap_image_vectors_new=0`, the intentionally empty historical image collection. Vision remains unavailable and Local AI therefore degraded, but `LIVE_VISION_REQUIRED_FOR_CORE_PILOT=false`; this is not a Core Pilot failure, provided no visual evidence is fabricated.

## 2. Original Product Goal

FarMap is an Agricultural AI Decision Platform whose commercial loop is evidence-grounded and owner-scoped:

`Farm/Field Context → Multisource Evidence → Persisted Diagnosis → Recommendation → Agent Plan → ProposedAction → Human Approval → OperationsTask → Outcome → Expert Review → HistoricalCaseCandidate`

The goal is not an unconstrained chatbot. MySQL holds canonical business facts, Spring Boot enforces identity, lifecycle, write safety, and product contracts, Python supplies intelligence capabilities, and Milvus stores derived vector indexes. REAL evidence must stay distinguishable from DEMO and unavailable data must never be replaced by synthetic values.

## 3. Current Commercial Status

| Area | Frozen status | Current interpretation |
|---|---|---|
| Commercial MVP V1 | **FROZEN** | No regression found in this audit |
| Core Pilot | **PILOT CANDIDATE GO** | Core knowledge/context/product loop is usable |
| Production Rollout | **CONDITIONAL** | Operational, security, provider, and observability gates remain |
| Frontend 5.1.1–5.1.6 | **DONE** | Capability gaps below are data/backend integration gaps, not a redesign mandate |
| Backend 5.2.1–5.2.6 | **DONE** | Write safety through Knowledge Backend V1 complete |
| Final Product Loop E2E | **DONE** | Frozen result retained |
| Phase 1.7 | **NOT STARTED** | Must not be inferred as the immediate next milestone |

Authoritative freeze: `docs/commercial-mvp-v1-freeze.md`.

## 4. Current Architecture

The architecture remains **Modular Monolith First**:

| Layer | Authority |
|---|---|
| React/Vite frontend | Commercial presentation and explicit user interaction |
| Spring Boot | Commercial/Product Control Plane; auth, ownership, stable IDs, lifecycle, approval and persistence |
| Python services | AI Intelligence Plane; local text/image embedding and optional vision |
| MySQL `zhgy` | Canonical business facts |
| Milvus | Derived knowledge and historical/image vector indexes |

Do not introduce Redis, Kafka, RabbitMQ, Spring Cloud, Graph DB, or a microservice split under the current freeze. Mongo is not part of the active canonical path; no running Mongo dependency was found or required by runtime health.

## 5. Frozen Domain Model

The following are distinct domain objects and transitions, not aliases:

- `Recommendation ≠ AgentAction`
- `AgentAction ≠ Approval`
- `Approval ≠ OperationsTask`
- `Diagnosis ≠ RAG run`
- `diagnosisId ≠ sourceRunId`
- `Evidence Sufficiency ≠ Diagnosis Confidence ≠ Retrieval Score ≠ Human Approval`
- `DEMO ≠ REAL`
- `EMPTY ≠ UNAVAILABLE`
- `READY_EMPTY ≠ exists data`
- `STALE ≠ NORMAL`
- `HistoricalCaseCandidate ≠ HistoricalCase`
- `Memory ≠ Evidence ≠ Knowledge ≠ HistoricalCase`
- `Memory ≠ Authorization ≠ Approval`

Write flow remains fail-closed: a supported, sufficiently grounded proposed action still requires explicit human approval before an operations task is created. A candidate may not automatically embed, train, or write Milvus.

## 6. Stable Identity Model

Required stable IDs are: `farmId`, `fieldId`, `observationId`, `diagnosisId`, `findingId`, `evidenceId`, `recommendationId`, `runId`, `actionId`, `approvalId`, `taskId`, `outcomeId`, `reviewId`, `candidateId`, `caseId`, `sourceId`, `documentId`, `versionId`, `conversationId`, `turnId`, and `memoryId`.

Business relationships must not depend on title, list index, display name, “latest”, “nearest”, LLM guessing, or natural-language matching. Legacy map IDs are deterministic: `legacy-farmap-location-{legacyId}` and `legacy-farmap-crops-{legacyId}`. Current field and product-loop entities use server-owned UUID-style IDs.

## 7. Commercial Frontend Status

| Milestone | Status |
|---|---|
| 5.1.1 Overview Command Center | DONE |
| 5.1.2 AI Diagnosis Productization | DONE |
| 5.1.3 Agricultural AI Analyst / Agent Workspace | DONE |
| 5.1.4 Operations Center | DONE |
| 5.1.5 Knowledge & Expert Feedback Center | DONE |
| 5.1.6 Commercial Contract Hardening & Domain Freeze | DONE |

The latest Overview integration now:

1. reacts to explicit `activeFarmId` in `farmap-frontend-dev/src/features/overview/adapter.ts`;
2. calls the REAL Operations API;
3. filters tasks by current-farm stable field IDs;
4. preserves weather `READY`, `READY_EMPTY`, and `UNAVAILABLE`;
5. no longer displays hard-coded `24°C / 多云`;
6. displays the real health unavailability reason;
7. passed REAL farm switch `1 → 8` verification; and
8. passed Copilot context-switch verification.

Farm 1 currently renders 549/549 map points and two pending tasks. Farm 8 renders 27/27 map points and valid empty states. Diagnosis, health, and risk remain empty/unavailable because their required safe aggregate/list contracts do not exist, not because a frontend redesign is needed.

## 8. Backend Product Loop Status

| Milestone | Status |
|---|---|
| 5.2.1 Production Write Safety | DONE |
| 5.2.2 Diagnosis Persistence | DONE |
| 5.2.3 Stable Product Loop Relationships | DONE |
| 5.2.4 Operations Lifecycle | DONE |
| 5.2.5 Expert Review V2 | DONE |
| 5.2.6 Knowledge Backend V1 | DONE |
| Final Product Loop E2E | DONE |

Current mutable database state: 15 agent runs, 16 approvals, 24 diagnoses, 13 operations tasks, 7 outcomes, 2 Expert Review V2 records, and 2 eligible historical-case candidates. Runtime reports Agent and Operations `READY`, persisted in MySQL/database mode. Approval, task completion, and relationship/idempotency boundaries remain server-authoritative.

## 9. Knowledge / RAG Status

Frozen Production Knowledge corpus:

- Original corpus: 37 files.
- READY: 26 text-extractable documents.
- Unsupported/corrupt/no-text: 11, explicitly classified rather than silently indexed.
- Embedding: BAILIAN `qwen3.7-text-embedding-flash`, 1,024 dimensions.
- Production collection: `farmap_knowledge_chunks_qwen3_v1`.
- Runtime row count: **507**, status `READY`.
- Reranker: BAILIAN `gte-rerank-v2`, status `READY`.
- Compatibility collection: `farmap_knowledge_chunks_v1`, BGE 512 dimensions, 4 rows, `READY`.

The production diagnosis retrieval path is:

`Query → Qwen embedding → Milvus candidate Top-N (20) → GTE rerank → Top-K (5) → Evidence`

Current relational metadata: 5 active sources, 29 active documents, 28 READY ingestions, 29 versions, and 511 chunks. Of the ingestions/chunks, 26/507 belong to the Qwen production path and 2/4 to the local BGE compatibility path. The production knowledge collection is not empty and must not be rebuilt.

## 10. Historical Vector Store Status

`farmap_image_vectors_new` is a separate 512-dimensional L2/IVF_FLAT collection. Current runtime state is collection exists, `rowCount=0`, vector store `READY`, retriever/history `READY_EMPTY`. This is the zero seen in runtime health; it is not the Qwen knowledge collection.

The empty state is expected under the frozen contract. `HistoricalCaseCandidate` records remain relational candidates only. They cannot be automatically promoted, embedded, trained on, or written to Milvus. No fake fill is permitted.

## 11. AI Engine Phase 1.1–1.6

| Phase | Capability | Frozen decision |
|---|---|---|
| 1.1 | Evidence Sufficiency + unsupported-action prevention | **GO / FROZEN** |
| 1.2 | Tool failure classification + bounded recovery | **GO / FROZEN** |
| 1.3 | Bounded replanning + dynamic plan adaptation | **GO / FROZEN** |
| 1.4 | Intent Router + Context Resolver V1 | **Core GO** |
| 1.5 | Conversation state + short-term memory | **Core GO** |
| 1.6 | Structured long-term memory V1 | **Core GO** |
| 1.7 | Not authorized/implemented | **NOT STARTED** |

Current pipeline:

`Natural Language → Intent Router → Context Resolver → Short-term Memory → Long-term Memory → Persisted Diagnosis → Recommendation → Planner → Tool Registry → Tool Execution → Failure Classification → Bounded Recovery → Bounded Replanning → Evidence Sufficiency → ProposedAction → Human Approval → Operations`

Short-term memory persists Conversation, bounded Turns, validated active IDs, pending clarification, deterministic summary/context budget, CAS versioning, and `clientMessageId` idempotency. Long-term memory uses `memory-UUID`, types `USER_PREFERENCE`, `FARM_PREFERENCE`, `FIELD_PREFERENCE`, `OPERATING_CONSTRAINT`, `COMMUNICATION_PREFERENCE`; scopes `USER`, `FARM`, `FIELD`; lifecycle `ACTIVE`, `SUPERSEDED`, `REVOKED`. Current MySQL counts are conversations 0, turns 0, and long-term memories 0: capability exists, but there is no live memory content to claim.

## 12. REAL Authentication Status

- Login: PASS.
- `userId=1`, `username=guest`, `mode=REAL`.
- Authorized Farm API result: `[1,8]`.
- Farm 7 direct access: `403 FARM_ACCESS_DENIED`.
- Cross-user Farm Leakage: 0.
- REAL → DEMO leakage observed in the verified flow: 0.

The previously observed DEMO/login issue is closed and retained only as history. No credential, password, JWT, API key, token, or hash is recorded here.

## 13. Multi-Farm Status

REAL browser verification passed farm selector visibility, farm switch 1→8, map switching, and Copilot context switching. User 1 sees 云阳 and 大足 only. 巫山 is not “missing”; it is **NOT AUTHORIZED** for this actor.

The current `farm` table contains four rows: the three production-source farms 1/7/8 plus farm 9 `PHASE_G_PILOT_FARM`, owned by a pilot/test actor. Farm 9 and its 12 fields are test/pilot support data and must not be confused with the three-farm legacy restoration.

## 14. Manual Data Migration Status

Manual Import V2: **GO**. Manual Verify V2: **GO**. ERROR 1136: **CLOSED**.

The canonical import used only `location` and valid `crops` rows to populate `farm_map_points`; it did not transform weather, guidance, accumulated temperature, fields, sensors, or health aggregates. Final counts:

| Scope | Boundary | Crop observation | Total |
|---|---:|---:|---:|
| Farm 1 云阳 | 68 | 481 | 549 |
| Farm 8 大足 | 0 | 27 | 27 |
| Farm 7 巫山 | 921 | 0 | 921 |
| Global | 989 | 508 | 1,497 |

Integrity: `duplicate_source_identity=0`, `invalid_coordinate=0`, `orphan_farm_reference=0`, `excluded_crop_identity_present=0`. Two orphan crop rows (`farm_id=2`, legacy IDs 484/485) were excluded; the 68-row `farmlocation` path was excluded as a duplicate/non-authoritative source and includes one swapped invalid coordinate.

`E:\FarMapTools\farmap-data-migration\reconciliation\target-reconciliation.json` is a **historical pre-import blocked artifact** and is not the current target truth. Current MySQL counts plus the successful Manual Verify V2 are authoritative. Do not rerun the import.

## 15. Current Three-Farm Data State

The following is actor-aware for user 1 unless noted:

| Capability | 云阳 (farm 1) | 大足 (farm 8) | 巫山 (farm 7) |
|---|---:|---:|---:|
| Ownership | user 1 | user 1 | user 9; **NOT AUTHORIZED** |
| Current stable fields | 1 | 0 | NOT AUTHORIZED (DB has 0) |
| Map points | 549 | 27 | NOT AUTHORIZED (DB has 921) |
| Boundary vertices | 68 | 0 | NOT AUTHORIZED (DB has 921) |
| Crop observations | 481 | 27 | NOT AUTHORIZED (DB has 0) |
| Source/current weather rows | 12 | 0 | NOT AUTHORIZED (DB has 12) |
| Accumulated-temperature rows | 582 | 0 | 0 |
| Guidance rows | 72 | 0 | 72 |
| Sensor data | no source/table | no source/table | no source/table |
| Actor-owned diagnoses | 7 | 0 | NOT AUTHORIZED |
| Actor-visible current-farm tasks | 3 (2 OPEN, 1 COMPLETED) | 0 | NOT AUTHORIZED |
| Health aggregate | not implemented | not implemented | NOT AUTHORIZED |
| Risk aggregate/list | not implemented | not implemented | NOT AUTHORIZED |
| Knowledge relation | global curated corpus; no stable farm FK | same | NOT AUTHORIZED in farm UI |
| UI visibility | visible | visible | hidden correctly |

The database also contains test/pilot diagnoses for other actors: 2 on farm 1 with a non-current field ID, 2 on farm 7, and 13 on farm 9. They are not user 1 Overview data.

## 16. Original Data Source Audit

Audited root: `E:\FarMapTools\farmap-data-migration` only. Manifest source is an authorized read-only legacy FarMap MySQL snapshot. Source inventory:

| Source | Rows | Key fields and meaning | Farm distribution / range |
|---|---:|---|---|
| `source/farm.sql` | 3 | `id,name,type,address,zoom,center,user_id` | 1 云阳/citrus/user1; 7 巫山/plum/user9; 8 大足/rice/user1 |
| `source/component.sql` | 4 | map/guidance/weather/statistic component definitions | global definitions |
| `source/farm_component.sql` | 4 | farm-to-component relation | all four rows belong to farm 1; none for 7/8 |
| `source/location.sql` | 989 | `id,farm_id,lon,lat` | farm1=68, farm7=921; stable points, not fields |
| `source/farmlocation.sql` | 68 | duplicate location path | farm1 only; excluded |
| `source/crops.sql` | 510 raw / 508 valid | point ID, farm, date, lat/lon, phenology, growth/impact, disease, K/P/N, volume, transmittance, image, yield, fruit, rate, diseases, severity | farm1=481 on 2024-06-26; farm8=27 on 2026-01-15; farm2=2 orphan excluded |
| `source/weather.sql` | 24 | `id,farm_id,farm_type,month,text` | farm1=12 and farm7=12, month 0–11; farm8=0 |
| `source/guidances.sql` | 144 | `id,farm_id,farm_type,month,guidance_type,text,is_formula` | farm1=72, farm7=72; month 0–11; types include body/fertite/park/pest |
| `source/accumulated_temperature.sql` | 582 | `id,farm_id,date,temperature,accumulated_temp` | all farm1; 2024-01-01 through 2025-08-04 |
| `source/models.sql` | 5 | legacy model metadata/URLs | 2 farm1; 3 on farms 2/3; URLs are not proven production-valid |

No source file/table or domain token was found for field, plot, block, parcel, sensor, irrigation, monitoring, or soil. The legacy snapshot therefore has coordinates and point observations but **SOURCE HAS NO STABLE FIELD MODEL** and no sensor stream model.

Agronomic detail quality is uneven. Farm 1 crop points all contain non-zero volume/yield/rate and one image URL across the set, but disease fields are largely placeholders and several nutrient/growth columns are null or zero. Farm 8 crop points contain growth/impact/K/P/N flags or values, but no volume/yield/rate/image and no meaningful disease signal. These are point observations, not parcels or sensor time series.

## 17. Weather Data Audit

### End-to-end trace

1. **Original source:** `source/weather.sql`, 24 rows. Columns are `id,farm_id,farm_type,month,text`; farm 1 and farm 7 each have month indexes 0–11. There is no date, timestamp, observed temperature, rainfall, humidity, forecast horizon, or station/provider identity.
2. **Migration/current MySQL:** the same 24 rows are present in `zhgy.weather` (farm1=12, farm7=12, farm8=0). They were not part of the 1,497-point map import because they already occupy their legacy table.
3. **Historical numeric weather-like data:** `zhgy.accumulated_temperature` has 582 farm-1 rows from 2024-01-01 to 2025-08-04 with date, temperature, and accumulated temperature. This is a valid historical numeric series, but it is not live weather or a forecast.
4. **Legacy API:** `GET /weather/intro` reads all 12 monthly prose buckets by `farmId` and can fall back by `farmType`. `GET /weather/accumulated-temperature` is legacy and currently hard-codes farm 1 internally; it is not a safe multi-farm commercial contract.
5. **Commercial field context:** `FarmFieldService.weather(farmId)` selects the current month by farm. No row means `UNAVAILABLE`; a row means `READY_EMPTY`, `sourceMode=MYSQL`, `scope=FARM`, `granularity=MONTHLY`, with the prose in `summary` but null `dataDate/observedAt`.
6. **Evidence:** `WeatherEvidenceRetriever` accepts trusted weather only when status is `READY` and `rainfall14d` is numeric. Seasonal prose therefore emits no weather evidence. `AccumulatedTemperatureEvidenceRetriever` is a separate historical evidence source.
7. **Frontend/UI:** Overview preserves the backend status and reason. It no longer fabricates header temperature/weather. For farm 1 it displays “暂无可信观测天气序列”/the backend reason rather than numeric weather; farm 8 has no row and no field, so weather is unavailable as expected.

### Root cause

The root cause is chiefly **source schema/trust-semantic mismatch plus incomplete product integration**, not a missing farm-1 database import. The source weather table is seasonal agricultural prose indexed by month, while the commercial weather/evidence contract intentionally requires a trusted observed series with timestamp and numeric measurements. There is also no production realtime provider. Farm 8 has an actual source coverage gap (zero weather rows), and the legacy accumulated-temperature endpoint has a multi-farm contract defect.

### Permitted future use

- Seasonal prose can be exposed as explicitly labeled **seasonal/historical agronomic context or guidance**.
- Accumulated temperature can be exposed as dated **historical observation/context/evidence**, with farm ownership enforced.
- Neither may be relabeled as realtime weather, forecast rainfall, or current temperature.
- The future fix belongs in a versioned backend contract/API split—`seasonal guidance`, `historical observed/accumulated temperature`, and `live/forecast weather`—followed by explicit frontend presentation. Do not weaken current trust semantics.

Relevant code: `FarmFieldService.java` weather/accumulated methods, `WeatherController.java`, `WeatherEvidenceRetriever.java`, and `AccumulatedTemperatureEvidenceRetriever.java`.

## 18. Field Data Audit

Current `farm_fields` has 13 rows globally: one actor-owned farm-1 field and 12 pilot fields on farm 9. Farm 8 and farm 7 each have zero. The farm-1 field is a runtime/product-created `MYSQL` field (`field-c3c5c1de-67f7-4111-a263-a6e52d856e08`), not a reconstructed legacy parcel.

Targeted source audit found no field/plot/block/parcel entity or stable field identifier. `location.id` and `crops.id` identify points only; grouping them into fields would be a new inference and would violate stable-ID rules. Therefore:

**SOURCE HAS NO STABLE FIELD MODEL.**

大足 `farm_fields=0` is a source-model limitation, not a proven migration omission. A future field onboarding/reconciliation workflow may create explicit user-confirmed fields, but this audit does not authorize fake or inferred fields.

## 19. Map Data Audit

Map restoration is complete for the authorized source identities. `FarmMapDataService` reads `farm_map_points`, returns boundary vertices and full crop-observation metadata through `GET /api/farms/{farmId}/map-data`, and applies farm ownership before serving data. The frontend map renders the canonical points and can consume crop metadata such as date, crop, phenology, nutrient fields, volume, image URL, yield, fruit, diseases, and severity.

Current result: farm1 549/549 PASS, farm8 27/27 PASS, farm7 hidden for user1. The 1,497 figure is map points only; it must not be described as fields, weather records, sensors, or total agricultural records.

## 20. Diagnosis Data Audit

The task prompt's earlier count of 18 is stale. The current read-only MySQL count is **24**, all `COMPLETED` and `mode=real`:

- user1/farm1/current field: 7 diagnoses — `diagnosis-28a36d7b-0b25-4c75-b7b7-54e84f064c4e`, `diagnosis-e7fe73ef-ca6e-4a77-98ce-529b4ed4c141`, `diagnosis-c928a578-4804-433c-ab75-07e80b12ec03`, `diagnosis-c5eeeff4-9f2f-429a-9de2-938af34c9744`, `diagnosis-224bfaba-9085-4411-a9f4-9455cbd43da4`, `diagnosis-6674239f-143b-42a9-a2d0-841305c0b444`, `diagnosis-e77ee327-270e-48d5-84e2-1e8dbf76b015`.
- user42/farm1/non-current `field-http-524`: 2 diagnoses.
- user42/farm7/`A-12`: 2 diagnoses.
- user40/pilot farm9/stable pilot fields: 13 diagnoses.

Each persisted row links a stable `diagnosisId` to `userId`, `farmId`, `fieldId`, `sourceRunId`, lifecycle, and REAL mode. `DiagnosisQueryController` exposes only exact actor-scoped `GET /api/diagnoses/{diagnosisId}` (plus compatibility path), and the repository resolves by exact `diagnosis_id AND user_id`. There is no actor-scoped list/by-farm/by-field endpoint with a deterministic business ordering/selection contract.

Overview therefore correctly remains `Diagnosis=EMPTY_VALID`: it cannot safely discover which of multiple records is the current farm summary. Adding “latest arbitrary”, nearest, title/name matching, list index, or LLM selection would violate the frozen model. A future endpoint must explicitly scope owner+farm+field, define lifecycle eligibility and deterministic ordering, and return stable IDs.

## 21. Operations Data Audit

Current database: 13 tasks, 78 task events, 7 outcomes. Lifecycle includes OPEN/in-progress transitions and terminal COMPLETED/CANCELLED semantics, with versioning and stable links to action, diagnosis, run, recommendation, evidence, approval, and outcome. `GET /api/operations/tasks` is actor-aware; the current service also permits legacy owner-null rows, so Overview applies an additional exact current-farm field-ID filter.

For user1's current farm-1 field, there are 3 tasks: 2 OPEN and 1 COMPLETED. Overview displays two pending. Farm 8 has no stable field and no matching tasks, so `EMPTY_VALID`. Operations integration is now `READY`; this closes the earlier frontend omission where the REAL Overview adapter did not call the Operations API.

Current related state includes 7 outcomes, 2 Expert Review V2 rows, and 2 ELIGIBLE historical candidates. One user1 chain has diagnosis→task→outcome→review linkage. Candidate remains distinct from HistoricalCase.

## 22. Health / Risk Capability Audit

**HEALTH AGGREGATE NOT IMPLEMENTED.**

No formal `FarmHealthAggregate`, authoritative `HealthScore`, `FarmHealthService`, health table, or health API was found. The frontend has display components (`FarmHealthCommand`, `HealthScoreCard`) but the REAL adapter deliberately returns an unavailable reason rather than synthesizing a score. No canonical farm risk aggregate/list API was found either, so decision queue and risk priorities remain valid empty states.

The source does contain limited crop-condition columns (phenology, growth, impact, nutrient values/flags, volume/yield/rate, disease fields), but disease values are largely placeholders and there is no soil table, sensor anomaly stream, or stable field relation. Existing diagnoses also cannot be collapsed into an arbitrary “latest” health score. Farm1 health is `UNAVAILABLE_EXPECTED`; farm8 is `EMPTY_VALID` because it has no stable field.

## 23. Vision Status

Runtime: Vision `UNAVAILABLE`; Local AI and Intelligence Plane `DEGRADED`; BGE text embedding `READY` at 512 dimensions; CLIP image embedding `READY` at 512 dimensions.

Frozen pilot policy is `LIVE_VISION_REQUIRED_FOR_CORE_PILOT=false`, so Vision unavailable **does not fail Core Pilot**. It does require zero fake visual evidence. The known `LocalVisionServiceTest` ONNX/CLIP host-memory OOM is classified as `TEST_RUNTIME_RESOURCE_LIMITATION`, not an Agent logic bug. The source has only one crop image URL and no trusted field-image binding; FieldContext correctly reports image context `READY_EMPTY` rather than inventing evidence.

## 24. Source→DB→API→UI Availability Matrix

`Yes` at one layer does not imply readiness at the next layer.

| Data Type | Original Source Exists | Imported/current MySQL | Backend Reads It | API Exposes It | Frontend Uses It | UI Visible | Current Status | Root Cause / Gap |
|---|---|---|---|---|---|---|---|---|
| Farm | Yes, 3 | Yes; 4 incl. pilot farm9 | Yes | Yes, actor-scoped | Yes | Yes | READY | Production source farms separated from pilot data |
| Field/plot/parcel | No stable entity | 13 product-created | Yes | Yes, actor-scoped | Yes | Yes/empty | PARTIAL | SOURCE HAS NO STABLE FIELD MODEL |
| Boundary | Yes, 989 `location` | Yes, 989 map points | Yes | Yes, farm-scoped | Yes | Yes | READY | None for restored points |
| Crop Observation | Yes, 510 raw/508 valid | Yes, 508 map points | Yes | Yes | Yes, map | Yes, map | READY/PARTIAL | Point data visible; not aggregated into health/risk |
| Crop | Yes via farm type/observations | Yes | Yes | Yes | Yes | Yes | PARTIAL | No standalone crop master/product analytics |
| Weather prose | Yes, 24 | Yes, 24 | Yes | FieldContext + legacy intro | Yes, status/reason | State visible; prose not promoted | PARTIAL | Seasonal prose lacks trusted observed/forecast schema |
| Accumulated temperature | Yes, 582 | Yes, 582 | Yes | FieldContext; legacy endpoint | Not used by Overview | No | PARTIAL | Historical series not productized; legacy endpoint hard-codes farm1 |
| Guidance/management | Yes, 144 | Yes, 144 | Legacy path exists | Legacy/NOT FULLY AUDITED | Not connected to Overview | No in Overview | PARTIAL | No current commercial farm/field contract |
| Soil | No | No dedicated table | Retriever reports unavailable | No provider data | State only | Unavailable | NOT AVAILABLE | No source/provider/schema |
| Sensor/Monitoring | No | No dedicated table | No canonical service | No | Placeholder only | “未配置” | NOT AVAILABLE | No source stream/device model |
| Irrigation | No | No dedicated table | No | No | No | No | NOT AVAILABLE | No source/domain model |
| Disease/Pest | Columns/guidance exist | Crop columns + guidance | Map service reads columns | Map API exposes metadata | Map can consume metadata | Limited map context | PARTIAL | Mostly placeholder disease data; no trusted aggregate |
| Tree/Plant | No stable entity | No dedicated table | No | No | No | No | NOT AVAILABLE | Crop points are not plant identities |
| Historical observation | Yes, crop dates + temperature | Yes | Map/temp retrievers read | Map/FieldContext partially | Map only | Partial | PARTIAL | Time series not unified/productized |
| Diagnosis | Runtime-generated, not legacy | Yes, 24 | Exact actor lookup | Exact-ID GET only | Detail flow yes | Overview empty | PARTIAL | No safe actor+farm+field list/selection API |
| Operations | Runtime-generated | Yes, 13 | Yes | Yes | Yes | Yes | READY | Overview exact field filtering applied |
| Outcome | Runtime-generated | Yes, 7 | Yes | Yes | Operations flow | Yes where linked | READY/PARTIAL | Sparse live coverage |
| Expert Review | Runtime-generated | Yes, V2=2 | Yes | Yes | Expert center | Yes | READY/PARTIAL | Sparse live coverage |
| Health | No aggregate | No aggregate | No | No | Display shell only | Reason only | NOT IMPLEMENTED | Formal aggregate/service/API absent |
| Risk | No aggregate | No aggregate | No current list | No | Empty structures | EMPTY_VALID | NOT IMPLEMENTED | Needs explicit evidence/lifecycle contract |
| Images/Vision | One legacy URL | URL retained; vectors 0 | Binding unavailable | Map URL/context state | Limited | No trusted evidence | READY_EMPTY/UNAVAILABLE | No trusted field relation; live Vision unavailable |
| Knowledge | Separate corpus, 37 files | Metadata 29 docs/511 chunks | Yes | Yes | Yes | Yes | READY | 26 supported; 11 explicit unsupported |
| Legacy models | Yes, 5 | Yes, 5 | Legacy AI path exists | NOT FULLY AUDITED | Not authoritative in Overview | No | PARTIAL/UNKNOWN | URLs not proven production-valid; do not equate with current providers |

## 25. Runtime Health Matrix

Read-only observation on 2026-09-08; all listed ports remained listening.

| Component | Status | Port | Dependency | Blocking Core Pilot? | Notes |
|---|---|---:|---|---|---|
| Frontend | READY | 5173 | Spring APIs | No | `http://localhost:5173/farmap/`; latest same-worktree build artifact present |
| Spring backend | REAL / READY | 8080 | MySQL, Python/Milvus by capability | Yes | Runtime contract `ai.v1` |
| MySQL `zhgy` | READY | 3306 | local service | Yes | Canonical facts; audited read-only |
| Milvus | READY | 19530 | local service | Yes for production knowledge | IPv6 loopback `::1`; two listeners observed |
| Python Local AI | DEGRADED | 8001 | model runtimes | No for current core | BGE/CLIP ready; Vision unavailable |
| Vision | UNAVAILABLE | 5000 listener | provider/model runtime | **No** | `LIVE_VISION_REQUIRED_FOR_CORE_PILOT=false` |
| Knowledge backend | READY | via 8080/19530 | MySQL + Milvus + BAILIAN | Yes | 29 versions; Qwen rows 507 |
| Agent | READY | via 8080 | MySQL/product services | Yes | 15 persisted runs |
| Operations | READY | via 8080 | MySQL | Yes | 13 tasks |
| Weather | READY_EMPTY/UNAVAILABLE | via 8080 | legacy MySQL rows | No | No trusted live/forecast series |
| Health Aggregate | NOT IMPLEMENTED | — | absent contract | No current pilot gate; product gap | Do not synthesize score |
| Mongo | NOT PRESENT/NOT REQUIRED | — | — | No | Not canonical runtime dependency |

Observed process ownership: MySQL PID 7812; Vision PID 3496; frontend PID 16772; Local AI PID 25644; backend PID 28836; Milvus PIDs 17160/21892. These are transient diagnostics, not deployment configuration.

## 26. Test Baseline

| Evidence | Result | Audit interpretation |
|---|---|---|
| Frozen Phase 1.6 baseline | **143 tests, 0 failures, 0 errors, 14 skipped** | Historical authoritative baseline; preserved |
| Current existing Surefire reports | **145 tests, 0 failures, 0 errors, 14 skipped** across 78 XML files | Latest repository artifacts (2026-09-07); **NOT RERUN DURING HANDOFF AUDIT** |
| Frontend production build | `pnpm build = PASS` | Passed immediately before this handoff audit on the same worktree; `dist` timestamp 2026-09-08 01:23 local; **NOT RERUN DURING HANDOFF AUDIT** |
| Full backend suite in this audit | NOT RERUN | Avoided unnecessary heavy/runtime-sensitive execution |

`LocalVisionServiceTest` remains outside the constrained full baseline because of the documented host-memory OOM. No PASS has been fabricated for that test.

## 27. Known Runtime Limitations

- Vision service is unavailable even though the port listener exists; Local AI health is degraded.
- Historical image Milvus collection is intentionally empty.
- Weather has no production realtime/forecast provider and no timestamped rainfall series.
- The current machine requires Java Milvus access through IPv6 loopback `::1`.
- Legacy `/weather/accumulated-temperature` is farm-1-fixed internally and should not be treated as a commercial multi-farm contract.
- No health aggregate, sensor feed, soil provider, or risk list exists.
- Current memory tables are empty despite implemented memory capabilities.
- The local MySQL CLI marker is not a usable client; read-only audit used the existing `D:\anaconda\envs\llm\python.exe` runtime without installing anything.

## 28. Known Product/Data Gaps

### A. Data migration/coverage gaps

- Weather, guidance, accumulated temperature, crop metrics, and legacy models exist beyond the 1,497 map points, but only map points were normalized into the new canonical map read model.
- Farm 8 has no source weather/guidance/accumulated-temperature rows.
- The source has no stable field, soil, sensor, irrigation, monitoring, or plant identity model; this is a source-model gap, not a failed row migration.
- Disease/image data is sparse or placeholder-heavy and lacks trusted field binding.

### B. Backend capability gaps

- HEALTH AGGREGATE NOT IMPLEMENTED.
- No actor-scoped diagnosis list/by-farm/by-field contract with deterministic selection semantics.
- Weather semantics are not split into seasonal guidance, historical observation, and live/forecast products.
- No canonical sensor/soil/risk service.
- Legacy accumulated-temperature endpoint is not multi-farm safe.

### C. Frontend integration gaps

- Accumulated-temperature history and seasonal weather prose are not presented as their actual semantics.
- Crop point metrics are map-level data, not integrated into evidence-backed health/risk summaries.
- Existing diagnoses cannot appear in Overview until a safe backend list/selection contract exists.
- Guidance and legacy model metadata are not part of the commercial Overview path.

### D. Runtime/infrastructure gaps

- Live Vision/provider availability, production weather provider, monitoring/alerting, TLS, secret management/rotation, rate limiting, backup/restore, and rollback rehearsal.

### E. Deferred AI work

- Phase 1.7 and later phases, semantic/vector memory, RAG V2 quality work, advanced Vision, reflection/self-correction, and multi-agent behavior remain deferred unless separately authorized.

## 29. Deferred Work

The audit did not implement any gap. Explicitly deferred: source-to-product data contract work, field onboarding/reconciliation, historical weather UI/API, realtime weather adapters, health/risk aggregation, diagnosis list API, sensor/soil/irrigation adapters, unsupported corpus conversion/OCR, historical-case promotion, Vision recovery, Phase 1.7/1.8, semantic memory, RAG V2, and multi-agent capabilities.

## 30. Security / Production Hardening Backlog

Before general production rollout: production TLS; centralized secret injection, rotation, and revocation; credential-free logs/docs; API rate limits/abuse controls; monitoring, metrics, traces, alerting, and ownership; backup/restore verification; disaster recovery and rollback rehearsal; audit retention; provider timeout/cost controls; browser/deployment matrix; and production Live Vision/weather adapters. Continue actor ownership checks on every farm/field/diagnosis/task/outcome/review/memory route. Never store or document plaintext credentials, JWTs, hashes, API keys, or tokens.

## 31. Worktree / Git State

The worktree is **intentionally dirty** on branch `feature/commercial-product`; preserve it exactly. Current `git status --short` summary after adding this handoff: 231 entries, comprising 79 tracked changes and 152 untracked entries.

| Category/path group | Entries | Representative scope |
|---|---:|---|
| `zhgy/` | 116 | Spring product loop, AI engine, field/map/migration/knowledge services, tests, Python compatibility |
| `docs/` | 58 | Commercial MVP, milestones, AI phases, audits, E2E evidence, including this handoff |
| `farmap-frontend-dev/` | 42 | Overview, diagnosis, agent, operations, knowledge, farm state, E2E/runtime artifacts |
| `scripts/` | 9 | local runtime, import/verify, baseline and smoke scripts |
| `local-ai/` | 2 | local intelligence runtime/contract test |
| `deployment/` | 1 | compose configuration |
| root entries | 3 | one encoded path plus `ip.txt` and `mysql.txt`; may contain local/runtime material |

Representative `git status --short` entries include modified frontend adapters/views/stores, modified Spring controllers/services/config/tests, and untracked phase documents, migrations, new product-loop classes, test suites, E2E evidence, and runtime directories. The full diff is intentionally not pasted into this handoff. Re-run `git status --short` before any future scoped change and treat every pre-existing edit as user-owned.

## 32. Frozen Constraints

- Do not start Phase 1.7 without explicit authorization.
- Do not re-import `farm_map_points`, rerun Manual Import V2, or change production data.
- Do not rebuild/reinitialize either Milvus collection.
- Do not create fake fields, weather, health, risk, diagnosis, visual evidence, or historical cases.
- Do not weaken REAL/DEMO, ownership, stable-ID, trust, evidence-sufficiency, approval, or idempotency semantics.
- Do not redesign architecture or add Redis/Kafka/RabbitMQ/Spring Cloud/Graph DB/microservice split.
- Do not reinstall environments, create a venv, or create Docker MySQL.
- Do not use `git reset`, `git reset --hard`, `git restore .`, `git checkout --`, `git clean`, `git clean -fd`, or `git switch`.
- Do not create a branch, commit, or push.
- Preserve the dirty worktree and never expose secrets.

## 33. Recommended Next Milestone

**Recommended Next Milestone: Original Agricultural Data → Commercial Product Integration V1 (read-contract first).**

Choose **Option A before Option B/Phase 1.7**. The system already has mature agent safety/recovery/replanning/memory mechanics, while real agricultural context is under-consumed. Advancing Phase 1.7 now would validate additional reasoning behavior against thin or semantically ambiguous weather/field/health/risk inputs and therefore reduce the real-world value of the evaluation.

This recommendation is not “migrate everything”. It should first define trustworthy, owner-scoped contracts for: (1) seasonal agronomic weather prose, (2) historical accumulated temperature, (3) realtime/forecast weather as a separately unavailable provider, (4) crop-observation analytics that do not pretend points are fields, and (5) explicit health/risk prerequisites. Field creation must be user-confirmed because the source has no stable field model. Sensor/soil/irrigation should remain unavailable until real sources exist.

## 34. Exact Next Steps

When a new implementation task is explicitly authorized:

1. Freeze a source-field dictionary and per-farm coverage report from the artifacts already audited; do not re-import.
2. Design three separate weather contracts: `SeasonalAgronomicContext`, `HistoricalTemperatureSeries`, and `LiveWeather/Forecast` with explicit trust/freshness states.
3. Make all new reads actor-scoped by stable farm/field IDs; retire the farm-1-hard-coded accumulated-temperature behavior from the commercial path.
4. Add a read-only agricultural-context API that exposes existing weather/guidance/temperature/crop metadata without changing evidence semantics.
5. Connect the frontend with honest labels and empty/unavailable states; no synthetic numeric weather.
6. Specify health/risk input prerequisites and scoring provenance before implementing an aggregate.
7. Separately design an actor+farm+field diagnosis list/selection API; never use arbitrary latest/nearest/name matching.
8. Add targeted contract, ownership, frontend build, and browser farm-switch tests.
9. Re-audit data utilization and then decide whether Phase 1.7 has enough real context to produce meaningful verification.
10. Keep all git operations and any production-data mutation outside scope unless the user separately authorizes them.

## 35. New Conversation Bootstrap Context

Use the following block as the authoritative compact starting point. Verify mutable runtime counts before acting, but do not reinterpret frozen decisions or overwrite the dirty worktree.

# Copy-Paste Context for New Chat

你正在继续 FarMap V2 农业 AI 决策平台。目标是用真实、可追溯、owner-scoped 的 Farm/Field 与证据完成 `Diagnosis → Recommendation → ProposedAction → Approval → Operations → Outcome → Review → Candidate` 闭环。禁止无约束执行或用假数据补缺口。

截至 2026-09-08：Commercial MVP V1=`FROZEN / NO REGRESSION`，Core Pilot=`PILOT CANDIDATE GO`，Production=`CONDITIONAL`。前端 5.1.1–5.1.6、后端 5.2.1–5.2.6 和 Product Loop E2E 已完成。AI Engine 1.1–1.3=`GO/FROZEN`，1.4–1.6=`Core GO`，1.7=`NOT STARTED`，不要擅自开始。

REAL 登录已验证：userId=1、username=guest、mode=REAL；授权 farms=`[1,8]`（云阳、大足）。farm7 巫山属于 user9，对 user1 返回 403 `FARM_ACCESS_DENIED`，跨用户泄漏为 0。不得输出任何密码、JWT、数据库凭据、API key、token 或 hash。

Manual Import/Verify V2=`GO`，ERROR 1136 已关闭。`farm_map_points=1497`：云阳 68 边界+481 作物=549；大足 27 作物；巫山 921 边界；全局 989+508。两个孤儿 crop 和重复 `farmlocation` 已排除，完整性计数均为 0。不要重新导入或重建 Milvus。

浏览器已验证 farm1→8、Copilot 上下文和地图。云阳有一个稳定 field（ID 见主文档），任务为 2 OPEN+1 COMPLETED；大足 fields=0。源数据没有 field/plot/block/parcel 或稳定 field ID：`SOURCE HAS NO STABLE FIELD MODEL`，不可拼点或造 field。

重要新发现：原始数据不止地图点。迁移源还有 weather 24 行（云阳12、巫山12、大足0，月度季节文字）、accumulated_temperature 582 行（云阳，2024-01-01 至 2025-08-04）、guidances 144 行、models 5 行及 crop 指标。这些表已在 `zhgy`，但未完整进入 Backend→API→Frontend→UI。

Weather 不是云阳未入库：`weather` 只有 month+text，没有观测时间戳、数值或 provider，因此 FieldContext=`READY_EMPTY`；证据层只接受 `READY`+numeric rainfall14d，不能削弱。季节文字可作标注后的 context/guidance，积温可作 historical evidence，均不能冒充实时天气。未来拆分季节说明、历史积温、实时/预报三个 contract；大足无 source weather，legacy 积温接口还固定 farm1。

当前 diagnoses=24，其中 user1/farm1/current field=7，均 REAL/COMPLETED。Overview=`EMPTY_VALID`，因为只有 exact-ID GET，没有 owner+farm+field 安全列表。保持 `Diagnosis ≠ latest arbitrary diagnosis`，不得按 latest/nearest/name/index/LLM 猜测。`HEALTH AGGREGATE NOT IMPLEMENTED`；Risk 也无正式 aggregate/list。

知识库正常：`farmap_knowledge_chunks_qwen3_v1=507`，Qwen 1024 维+reranker；37 文件中 26 READY、11 unsupported。`farmap_image_vectors_new=0` 是预期 `READY_EMPTY`。Candidate≠HistoricalCase，不能自动写向量。Vision=UNAVAILABLE，但 pilot flag=false，不阻断 Core Pilot；必须 zero fake evidence。Vision 测试 OOM 是资源限制，不是 Agent bug。

冻结不变量：Recommendation≠AgentAction；AgentAction≠Approval；Approval≠OperationsTask；Diagnosis≠RAG run；diagnosisId≠sourceRunId；Evidence Sufficiency≠Confidence/Score/Approval；DEMO≠REAL；EMPTY≠UNAVAILABLE；READY_EMPTY≠exists data；STALE≠NORMAL；Memory≠Evidence/Knowledge/HistoricalCase/Authorization/Approval。关系只用 stable IDs。

环境：frontend 5173、backend 8080、MySQL 3306/zhgy、Milvus `::1:19530`、Python `D:\anaconda\envs\llm\python.exe`、Local AI 8001、Vision 5000。不要重装、建 venv/Docker MySQL、重启或初始化集合。历史测试 143/0/0/14；现有报告 145/0/0/14，本审计未重跑；前序 `pnpm build` PASS。

worktree 有意 dirty：加入本文后 231 项（79 tracked、152 untracked）。旧改动属用户；严禁 reset/restore/checkout/clean/switch，不建 branch、不 commit、不 push。

推荐下一里程碑是 `Original Agricultural Data → Commercial Product Integration V1`，先以只读、owner-scoped、stable-ID contract 诚实接入季节天气、历史积温、guidance、crop observation，并定义 health/risk 前提和 diagnosis 安全列表；无真实 source 的 field/sensor/soil/irrigation 保持 unavailable。完成真实数据利用后再评估 Phase 1.7。先读 `docs/farmap-current-state-handoff-2026-09-08.md`，未经授权不要实现。
