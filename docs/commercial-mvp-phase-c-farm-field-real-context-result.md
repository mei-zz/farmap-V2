# FarMap Commercial MVP Integration — Phase C Result

## 1. Scope and release boundary

Phase C establishes the minimum real Farm / Field / FieldContext contract and wires Overview, Agent and Diagnosis to it. No Phase D work was started. Phase B Python `ai.v1` contracts and gateways were not changed.

## 2. Reality Audit

### Existing farm model

The current MySQL database contains `farm.id`, `farm.user_id`, name, type, address and centre. Farm list/detail APIs already exist through `/user/login` and `/user/get-farm`, with ordinary users restricted to their own farms by the existing login service.

### Existing spatial model

There are two legacy location tables: `location` (989 rows in the audited database) and `farmlocation` (68 rows). Their rows contain coordinates and a farm foreign key, but no stable business field identity, field name, boundary, ownership snapshot or crop relationship. They are therefore retained as legacy GIS points and not reused as canonical `fieldId` values.

### Existing crop, weather and temperature model

`crops` is farm-scoped and stores observations at coordinates; it has no field foreign key. `weather` contains monthly text rows per farm/type, not current observations or a rainfall series. `accumulated_temperature` is farm/date scoped and was available for farm 1. `WeatherApiService.getHistoricalWeatherData` still contains a synthetic generator, so Phase C does not call it for REAL context.

### Existing image and camera model

`user_requests` stores uploaded image references by user and coordinates, but has no farm/field foreign key. `agrirecord` is a legacy farm-address record. No trusted camera registry binds a camera to a field. Phase C exposes this honestly as farm-scope `READY_EMPTY`; it does not label an upload as a live camera.

### Existing frontend state

The Overview adapter previously imported `demoFarm`, `demoFields`, `demoWeather`, `demoInsights`, inline tasks and inline trends. The AI context store also defaulted to A-12 demo values. Phase C adds a real-first loader and neutral unavailable initial context; the existing visual modules remain in place.

## 3. Field identity decision

**Decision: Option B — minimum `farm_fields` master table.** There is no current trustworthy field entity. The new table uses a server-generated `field-<UUID>` primary key, a farm foreign key, a unique `(farm_id, name)` guard, optional crop/area/growth stage/coordinates, source mode and owner snapshot. No legacy rows are automatically mapped.

## 4. Data model and migration

Added `V5_3_1__commercial_field_master.sql`. It creates `farm_fields` with database-level primary key, farm foreign key and duplicate-name boundary. A runtime `CREATE TABLE IF NOT EXISTS` compatibility bridge is also present for the current environment, while the release still requires the versioned migration to be applied by the deployment process. The local MySQL migration was applied read-only for verification of table shape and currently contains zero seeded fields; no fake field was inserted.

## 5. APIs

Implemented:

- `GET /api/farms/{farmId}/fields`
- `POST /api/farms/{farmId}/fields`
- `GET /api/fields/{fieldId}`
- `GET /api/fields/{fieldId}/context`

The service resolves the authenticated actor from JWT/BaseContext, verifies the farm owner, generates field identity and timestamps, and returns closed 401/403/404/409 errors. No client-supplied identity or ownership is trusted.

## 6. Server-assembled FieldContext

`FarmFieldService.context` emits farm, field, crop, location/GIS, weather, accumulated temperature, image, soil and satellite objects with source, scope and status information. Soil and satellite are explicitly `UNAVAILABLE`. Weather comes only from the current MySQL monthly row; no random historical value is used. Legacy images are explicitly farm scope and `READY_EMPTY`. The root context is `MYSQL`/`READY` when the field master is valid, while individual sources can remain unavailable.

## 7. Diagnosis and Agent trust boundary

For a `field-*` request, the Diagnosis controller calls the field service before RAG validation. The server overwrites farm, crop, growth stage, weather and context with the trusted projection and clears client weather when no trusted weather exists; RAG therefore fails closed instead of accepting fabricated client facts. Agent run creation similarly overwrites field, farm, crop, coordinates, weather and source modes for a real field. Existing non-field/demo identifiers retain compatibility for the explicit demo path.

## 8. Overview real-first wiring

`getRealOverviewViewModel` loads the authenticated user’s selected/first farm, farm detail, field list and (when a field exists) FieldContext. The page now starts with an `UNAVAILABLE` neutral model, then loads REAL or explicit DEMO mode. It never catches a real API failure and substitutes demo values. With the audited empty `farm_fields` table, the honest state is REAL + `EMPTY` health, no decision queue, no recommendations, no operations tasks and no configured camera; farm map data remains available from the legacy farm detail contract.

The existing Overview visual structure is preserved. This phase changes the data boundary and status semantics rather than redesigning the frozen Commercial UI.

## 9. AI context and frontend semantics

The global AI context no longer defaults to A-12 demo facts. Overview writes source mode labels when a real farm is loaded. PageContextAdapter carries those labels to Agent requests. Diagnosis keeps the explicit `a12-demo`/demo-session path; a real route does not offer a demo fallback after an API failure.

## 10. Tests and verification

- MySQL connectivity verified with the supplied local credentials; existing farm/table counts and schemas were audited.
- `V5_3_1` applied to the local `zhgy` database; `farm_fields` exists with the expected primary key, foreign key and unique name index; row count remains 0.
- `git diff --check` passes; only line-ending warnings are reported for the pre-existing dirty worktree.
- Added `FarmFieldServiceTest` for fail-closed cross-user access and `FarmFieldMysqlTest` for the installed master-table boundary.
- Frontend `npx tsc -b --pretty false` passes.
- Frontend `npm run build` passed TypeScript but the default minified Vite render was blocked by the host Windows `VirtualAlloc`/esbuild out-of-memory failure. `npx vite build --minify=false` completed successfully (7,172 modules); the remaining failure is an environment/memory limitation of the default minifier, not a TypeScript error.
- Maven was not available on PATH in this environment, so the normal backend Maven compile and integration suite could not be run. A JDK 17 source compilation check was attempted; the repository-wide ad-hoc classpath includes conflicting Spring source jars, so it is not treated as a Maven result.
- No HTTP E2E was claimed because the backend was not rebuilt/launched in this shell. The endpoint contract is documented and the database schema boundary was verified directly.

## 11. Files added or modified for Phase C

Added:

- `zhgy/zhgy/src/main/resources/db/migration/V5_3_1__commercial_field_master.sql`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/field/FarmField.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/field/FieldContext.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/field/FieldAccessException.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/field/FarmFieldService.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/controller/FarmFieldController.java`
- `docs/commercial-field-context-contract-v1.md`
- `docs/commercial-mvp-phase-c-farm-field-real-context-result.md`

Modified:

- `WebMvcConfiguration.java` for field API authentication paths.
- `MigrationReadinessVerifier.java` and its existing boundary test for V5_3_1 schema readiness.
- `DiagnosisController.java` and `AgentController.java` for server-side real field context override.
- `farmap-frontend-dev/src/features/overview/adapter.ts` and `views/overview/index.tsx` for REAL-first loading.
- `farmap-frontend-dev/src/store/aiContext.ts`, `features/agent/PageContextAdapter.ts` and `views/diagnosis/Detail.tsx` for neutral/real context semantics.

The worktree already contained many earlier Milestone 5.2 and Phase B changes; they were preserved and not reset or cleaned.

## 12. Remaining P0/P1 risks

- **P0 release gate:** the repository still has no formal migration runner; `V5_3_1` must be applied by deployment before relying on the runtime bridge.
- **P0 product data gate:** the audited database has zero `farm_fields`; a production user must create/import verified field records before field-level diagnosis can be presented.
- **P1:** legacy image records, crops, weather and locations remain farm-scoped and cannot yet provide field-level history, live cameras, soil or satellite evidence.
- **P1:** backend Maven and HTTP E2E must be rerun in a Maven-enabled runtime after the source is rebuilt.
- **P2:** Overview’s existing UI modules still contain presentation fields designed for the frozen Commercial V1 model; future work should add source-aware component contracts rather than reintroducing fake values.

## 13. Phase C decision

**Code status: CONDITIONAL GO.** The stable Field identity, ownership boundary, server context contract and real-first frontend wiring are implemented. **Production status: CONDITIONAL GO** until the migration runner, at least one verified field per production farm, backend Maven/HTTP E2E, and real provider bindings for field-level weather/images/soil/satellite are available.

## 14. Phase D entry (not started)

Phase D may later address field import/boundary verification, field-level image binding, real weather/provider freshness, soil and satellite adapters, and corresponding evaluation. It was intentionally not implemented in Phase C; Agent planner/recovery, RAG/embedding, Knowledge upload, HistoricalCase and camera registry work remain out of scope.
