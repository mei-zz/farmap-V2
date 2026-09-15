# FarMap Website Usability Recovery V1 Result

Date: 2026-09-08  
Decision: **GO** for Website Usability Recovery V1. Commercial MVP remains **FROZEN / NO REGRESSION**; production rollout remains **CONDITIONAL**. Phase 1.7 was **NOT STARTED**.

## Runtime identity and authorization

- Test identity: `userId=1`, `guest`, REAL user path.
- Authorized farms: Farm 1 云阳 and Farm 8 大足.
- Farm 7 巫山 belongs to `user9`; `user1 -> farm7` returns HTTP 403.
- No ownership, map import, Milvus rebuild, branch, commit, or push was performed.

## Expert Consultation

**PASS.** The duplicate “专家咨询” iframe/panel was removed from Operations/agricultural guidance. The global FarMap Copilot remains available as the single consultation entry point. The final browser check found no expert iframe/panel and no page error.

## Agricultural Guidance

**PASS.** The frontend now uses the authorized farm-scoped read contract:

`GET /api/farms/{farmId}/guidance-context?month={month}`

- Farm 1, month 8: HTTP 200, `READY`, 6 records, with actual types `body`, `fertite`, `park`, and `pest`; all six records contained non-empty real body/formula text and were visible in Operations.
- Farm 8, month 8: HTTP 200, `EMPTY`, 0 records; the UI shows a compact empty state instead of large empty cards.
- The former farm-type fallback and the duplicate expert panel are not used.

## Legacy weather audit

- Legacy provider/configuration: **FOUND**. The existing Amap/Gaode integration has live weather and reverse-geocoding endpoints, and its deployment configuration is present. Secret values are intentionally not recorded here.
- Safe contract added: `GET /api/farms/{farmId}/current-weather`, owner-scoped through the canonical farm authorization check.
- Farm 1 and Farm 8: HTTP 200, `READY`, provider-backed current weather.
- Farm 7 for user1: HTTP 403.
- Current weather is explicitly separate from the existing seasonal prose table. No seasonal text is presented as current weather.
- The live response parser tolerates additional provider fields without fabricating values.
- Known legacy gap: the old scheduled historical-weather generator remains in legacy code, but it is not used by the new current-weather or agricultural-context paths and no generated history was added.

## Legacy camera / monitoring audit

- Legacy camera configuration: **FOUND**. The existing Hikyun token and preview integration is reachable in a direct provider probe; credentials/tokens are not recorded here.
- Legacy devices are exposed by the admin-only monitor path and are represented by a global hardcoded device list with coordinates matching the Wushan/Farm 7 area. There is no explicit authorized Farm 1/Farm 8 mapping.
- Farm 1 and Farm 8 therefore report `NOT_CONFIGURED`; no stream is shown or borrowed from Farm 7.
- Camera stream status and AI Vision status are separated: camera is not claimed as vision, and AI Vision remains `UNAVAILABLE`.

## Dynamic header date

**PASS.** The layout uses `Intl.DateTimeFormat` through `src/utils/currentDate.ts`, refreshed locally rather than using a hardcoded date. A deterministic browser test for 2026-09-08 passed, and the real browser rendered `2026年9月8日 星期二`. No stale `2026-09-04` or `2026年9月4日` literals remain under `farmap-frontend-dev/src`.

## Farm 1 Smart Farm

- Map: 549 records, consisting of 68 boundary records and 481 crop observations.
- Agricultural context: seasonal prose available; accumulated-temperature data available; current guidance available; the current field has the existing bounded diagnosis view.
- Current weather: `READY` and provider-backed.
- Camera: `NOT_CONFIGURED` for this authorized farm; AI Vision: `UNAVAILABLE`.
- Health, sensor, soil, and unsupported diagnosis signals remain honest/empty or unavailable; no synthetic score or diagnosis was added.

## Farm 8 Smart Farm

- Farm identity: 大足, crop mapping 水稻.
- Map/crop observations: 27; latest observation date 2026-01-15.
- Crop summary: 27 valid nutrient records, no valid yield value, 0 images, and no trusted disease signal (`缺少可信病害记录`).
- Boundary: unavailable; the UI states that the crop observation points are being shown instead.
- Current weather: `READY` and provider-backed.
- Seasonal prose, accumulated temperature, monthly guidance, and diagnosis: compact empty/unavailable states where the authorized data is absent.
- The page now prioritizes farm summary, map, and crop observation content, with compact coverage cards for missing capabilities.
- Browser verification showed no Farm 1 leakage: no 云阳/柑橘/481-observation content appeared after switching to Farm 8.

## Farm switching and leakage checks

**PASS.** Real user1 browser switching to Farm 8 completed successfully. The UI did not request `/api/farms/-1/...`, did not retain stale Farm 1 content, and produced no core 5xx responses or page errors. The cross-user Farm 7 API check returned 403.

## Fake-data audit

**PASS for the recovered paths.** No fake weather, camera stream, field, health score, sensor, soil, or diagnosis data was added. The old synthetic historical-weather method remains an explicitly known legacy gap and is not called by the new path. Existing demo timestamps were changed to local dynamic dates; no new business data was fabricated.

## Verification evidence

- Backend targeted regression: `CurrentWeatherServiceTest`, `AgriculturalContextServiceTest`, `FarmFieldServiceTest`, `DiagnosisPersistenceTest`, `OperationsLifecycleTest`, `LegacyFarmMapImportPolicyTest`, and `MigrationReadinessVerifierTest` — passed, exit 0.
- Frontend build: `pnpm build` (`tsc -b && vite build`) — passed, exit 0. Only existing large-chunk warnings were emitted.
- Deterministic date browser check — passed.
- Final real-browser gate: `date true operationsNoCrash true expertRemoved true guidanceText true farm8 true noFarm1Leak true compactCoverage true badFarmRequests 0 core5xx 0 pageErrors 0`.
- Screenshots: `farmap-frontend-dev/test-results/website-usability-operations.png` and `farmap-frontend-dev/test-results/website-usability-farm8-final.png`.

## Main files changed

- Backend: `CurrentWeatherResponse.java`, `CurrentWeatherService.java`, `CurrentWeatherController.java`, `WeatherApiService.java`, `GaodeWeatherResponse.java`, `AgriculturalContextResponse.java`, `AgriculturalContextService.java`, and their targeted tests.
- Frontend: agricultural context contract/types, overview adapter/types, `AgriculturalContextPanel.tsx`, Operations page, layout date utility, dashboard integration, demo timestamp cleanup, and related styles.
- Documentation: this result file.

## Services and remaining gaps

- Verified running: MySQL `3306`, frontend dev server `5173`, backend `8080`, local AI `8001`, and Vision `5000`.
- Milvus `19530` was not listening during this verification; backend startup logged a connection timeout but continued. No rebuild was attempted. Vector/RAG-dependent features remain a known platform gap.
- Other known gaps remain intentionally honest: no authorized Farm 1/Farm 8 camera mapping, AI Vision unavailable, and health/sensor/soil capabilities not configured.

## Frozen states and user action

- Commercial MVP: **FROZEN / NO REGRESSION**.
- Core agent product loop: retained as previously frozen GO; production rollout remains conditional on its prior gates.
- Phase 1.7: **NOT STARTED**.
- User action: refresh the browser, preferably a hard refresh, to load the latest frontend bundle from the already-running local server.
- Git state: no new branch, commit, or push; unrelated pre-existing worktree changes were preserved.
