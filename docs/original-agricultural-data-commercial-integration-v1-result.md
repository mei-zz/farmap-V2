# FarMap Original Agricultural Data → Commercial Product Integration V1

## Decision

**PARTIAL_GO**

The REAL read path is implemented and runtime-verified for the authorized user and farms. The backend/API contract, farm isolation, Overview integration, Smart Farm integration, honest empty states, and build/regression gates pass. The remaining qualification is a strict automated browser click-through of the new Smart Farm farm-switch UI; the authenticated API switch and the existing browser farm/map switch are verified, while the headless Ant Design popup interaction was flaky and is not counted as a new UI PASS.

## REAL user and authorization

- REAL user: `userId=1`, `username=guest`, `mode=REAL`.
- Authorized farms: `[1, 8]` (`云阳`, `大足`).
- Farm 7 (`巫山`) belongs to another owner; user 1 receives `403 FARM_ACCESS_DENIED`.
- New read paths authorize the farm before querying agricultural source data. No fallback by `farmType`, no cross-farm borrowing, and no first-record diagnosis selection were added.

## Implemented backend contract

Added typed, read-only, actor-scoped agricultural context:

- `GET /api/farms/{farmId}/agricultural-context?month=&temperatureLimit=&diagnosisLimit=&fieldId=`
- `GET /api/farms/{farmId}/seasonal-context?month=`
- `GET /api/farms/{farmId}/historical-temperature?limit=`
- `GET /api/farms/{farmId}/guidance-context?month=`
- `GET /api/farms/{farmId}/crop-observation-summary`
- `GET /api/farms/{farmId}/diagnoses?fieldId=&status=&limit=`

The response explicitly separates `READY`, `EMPTY`, `UNAVAILABLE`, `NOT_CONFIGURED`, and `NOT_IMPLEMENTED`, and includes source/provenance. Historical temperature is returned as a bounded series with summary metadata. Diagnosis results are a bounded list with stable IDs; list does not imply current authoritative selection.

## Runtime results

### Seasonal Context

- Farm 1: `READY`; monthly seasonal agronomic/climate prose is available from the 12 source rows. The UI labels it `本月农情` / seasonal reference and never presents it as realtime weather.
- Farm 8: `EMPTY`; no farm-8 seasonal source row exists.

### Historical Temperature

- Farm 1: `READY`; 582 source observations, date range `2024-01-01 → 2025-08-04`. Overview requests a bounded 90-point window and displays the latest accumulated value plus date range/count.
- Farm 8: `EMPTY`; no farm-8 historical temperature is borrowed.

### Guidance

- Farm 1: `READY`; 72 source rows exist. The current-month read returns 6 real entries.
- Farm 8: `EMPTY`; no farm-8 guidance is borrowed.

### Crop Observation Summary

- Farm 1: `READY`, 481 canonical crop observations.
- Farm 8: `READY`, 27 canonical crop observations.
- Summary fields are derived only from existing map observation rows. Placeholder disease/nutrient values are not converted into health or disease claims.

### Diagnosis List

- Farm 1: `READY`; the current REAL user/farm/current-field source has 7 completed records. The default API/UI response is deliberately bounded to 5, with optional field/status/limit filters. Each row navigates by its exact `diagnosisId`.
- Farm 8: `EMPTY`.
- Farm 7 for user 1: `403`.

### Operations

- Farm 1: `READY`; existing current operations remain `2 OPEN + 1 COMPLETED`.
- Farm 8: `EMPTY`.

### Map

- Farm 1: `549` points (`68 boundary + 481 crop observations`).
- Farm 8: `27` crop observations.
- No map import or `farm_map_points` mutation was performed.

### Data availability and deferred capabilities

- Farm 1: map, fields, seasonal context, historical temperature, guidance, crop observations, diagnoses, and operations are available as applicable.
- Farm 8: only the real map/crop observation summary is populated; the other absent source classes remain honest `EMPTY`.
- Realtime Weather: `UNAVAILABLE`.
- Vision: `UNAVAILABLE`.
- Sensor: `NOT_CONFIGURED`.
- Soil: `NOT_CONFIGURED`.
- Health aggregate: `NOT_IMPLEMENTED`; the UI exposes prerequisite/status information only and emits no numeric score.
- Risk aggregate, field inference, sensor/soil integration, realtime weather, OCR, RAG V2, semantic memory, multi-agent work, and Vision/Milvus rebuilds were not started.

## Frontend productization

- Overview keeps the existing Command Center and adds a REAL-only agricultural context panel.
- Smart Farm adds the same farm-scoped context panel beside the existing map/controls.
- The panel has loading, ready, empty, and error states for seasonal context, temperature, guidance, crop observations, diagnoses, and availability.
- The weather header no longer implies a current temperature/forecast; it shows the unavailable state when no realtime provider exists.
- Farm 8 renders its own empty states rather than retaining farm-1 context. The adapter and panel are keyed by the selected `farmId`.
- No fake weather, forecast, sensor, soil, field, disease, diagnosis, task, visual evidence, or health score was introduced.

## Verification

- Backend targeted test command passed: `AgriculturalContextServiceTest`, `FarmFieldServiceTest`, `DiagnosisPersistenceTest`, `OperationsLifecycleTest`, `LegacyFarmMapImportPolicyTest`, `MigrationReadinessVerifierTest` — 0 failures, 0 errors.
- Backend compile passed with the local Maven runtime after the final Operations availability change.
- Frontend `pnpm build` passed after the final UI text change; only the pre-existing large-chunk warnings remain.
- Runtime browser/API smoke passed using the REAL local session: Overview loaded in REAL mode, agricultural context rendered, no fake-weather marker was detected, and the health boundary was visible. API results were farm 1 `200`, farm 8 `200`, and farm 7 `403` with the statuses/counts recorded above.
- Existing authoritative handoff baseline remains 145 Maven tests, 0 failures, 0 errors, 14 skipped. A new full Maven run was not repeated in this task; the related regression set and compile were rerun.
- No database writes, data imports, or Milvus writes were performed.

## Files added

- `zhgy/zhgy/src/main/java/com/mei/zhgy/vo/AgriculturalContextResponse.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/AgriculturalContextService.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/controller/AgriculturalContextController.java`
- `zhgy/zhgy/src/test/java/com/mei/zhgy/service/AgriculturalContextServiceTest.java`
- `farmap-frontend-dev/src/features/agricultural/context.ts`
- `farmap-frontend-dev/src/features/agricultural/AgriculturalContextPanel.tsx`
- This result document.

## Files modified

- `farmap-frontend-dev/src/types/overview.ts`
- `farmap-frontend-dev/src/features/overview/adapter.ts`
- `farmap-frontend-dev/src/views/overview/index.tsx`
- `farmap-frontend-dev/src/views/dashboard/index.tsx`
- `farmap-frontend-dev/index.css`

## Known remaining gaps

1. The new Smart Farm farm-switch UI needs one clean headed-browser click-through to upgrade the qualification from `PARTIAL_GO` to `GO`; the API switch and existing farm/map browser switch are already proven.
2. Farm 8 has no stable field model, so no field is displayed or inferred.
3. Realtime weather, trusted health/risk aggregation, sensor, soil, and vision evidence remain unavailable or not configured.
4. The default temperature and diagnosis payloads are intentionally bounded; full history remains available through the bounded endpoint parameters.

## Frozen states and services

- Commercial MVP V1: **FROZEN / NO REGRESSION**.
- Phase 1.7: **NOT STARTED**.
- Existing local services were kept running: backend `8080`, frontend `5173`, MySQL `3306`, Milvus `19530`, local AI `8001`, and Vision `5000`.
- Worktree was intentionally dirty and existing user changes were preserved.
- **NO COMMIT**.
- **NO PUSH**.

## User refresh path

Open or refresh:

`http://localhost:5173/farmap/`

Then inspect `Overview → Smart Farm` and switch between `云阳` and `大足`. Do not start the next milestone automatically.
