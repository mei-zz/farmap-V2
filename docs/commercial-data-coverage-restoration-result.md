# FarMap Commercial Data Coverage Restoration

## 1. Executive Summary

The authorized legacy FarMap deployment was inspected read-only. Its MySQL database is the authoritative source for three real farms: 云阳, 巫山, and 大足. The legacy map dataset contains 1,497 canonical map records after explicit exclusion of two orphan crop rows: 989 boundary vertices and 508 crop observations. A V2 canonical `farm_map_points` schema, API, frontend adapter, deterministic importer, and regression coverage were added. The target database could not be authenticated, so target backup and target import were not executed. Decision: **NO-GO until target backup/import and live reconciliation are completed**.

## 2. Reason for Audit

Commercial V2 exposed only a small demo-shaped map subset while the legacy production page exposed materially more real data. The audit was required to distinguish migration loss from API coverage, canonical mapping, ownership, frontend fixture, or data-source problems without treating a screenshot as the migration source.

## 3. Legacy Deployment Topology

The authorized server runs the legacy FarMap stack in Docker. The relevant path is frontend → `/user/get-farm?farmId=...` → Spring backend/FarmMapper → MySQL database `zhgy`. The source services remained running throughout discovery and export.

## 4. Source Container(s)

Relevant containers were `farmap-frontend-1` (`farmap-frontend`, loopback `24080→80`), `farmap-backend-1` (`farmap-backend`, loopback `24009→8080`), and healthy `farmap-mysql-1` (`mysql:8.0.36`) with persistent MySQL storage. Only read-only Docker inspection, `docker exec`, SELECTs, and dumps were used.

## 5. Legacy Data Source

The authoritative source is MySQL database `zhgy` in `farmap-mysql-1`. Relevant exported tables were `farm`, `component`, `farm_component`, `location`, `farmlocation`, `crops`, `weather`, `guidances`, `models`, and `accumulated_temperature`. Source table totals include 3 farms, 989 `location` rows, 68 `farmlocation` rows, 510 crop rows, 24 weather rows, 144 guidance rows, 5 model rows, and 582 accumulated-temperature rows.

## 6. Legacy API / Repository Trace

The legacy frontend farm selector requests `/user/get-farm?farmId=...`. The backend `FarmMapper` reads farm metadata, `location` rows, and crop rows; the legacy bundle consumes `farm.locations` as an AMap polygon/path and `farm.crops` as georeferenced crop data. The frontend constructs `AMap.LngLat(longitude, latitude)` directly, with no coordinate transformation.

## 7. Farm Inventory

The source farm rows are: 云阳 `id=1`, type `citrus`, owner user `1`, center `108.1302,31.1706`, zoom `18`; 巫山 `id=7`, type `plum`, owner user `9`, center `109.8195,31.1434`, zoom `10`; 大足 `id=8`, type `rice`, owner user `1`, center `105.7864,29.2668`, zoom `15`. No trustworthy legacy field/plot entity was found; `location` is a map boundary path, not a field table.

## 8. 云阳 Inventory

Source farm ID `1`, type `citrus`, owner user `1`. It has 68 authoritative boundary vertices and 481 crop observations, for 549 canonical map points. Crop observations are dated `2024-06-26`; weather has 12 rows and guidance has 72 rows. The crop rows preserve yield, rate, disease, phenology, growth, nutrient, image, and other source attributes where present.

## 9. 大足 Inventory

Source farm ID `8`, type `rice`, owner user `1`. It has no source `location` boundary vertices and 27 crop observations, for 27 canonical map points. The crop observations are dated `2026-01-15` and retain the source measurement attributes. No weather or guidance rows were found for farm ID `8` in the audited source tables.

## 10. 巫山 Inventory

Source farm ID `7`, type `plum`, owner user `9`. It has 921 authoritative boundary vertices and no crop observations, for 921 canonical map points. Weather has 12 rows and guidance has 72 rows. No crop type was invented for the absence of crop observations.

## 11. Crop Inventory

The proven crop breakdown is 云阳 `citrus=481`, 大足 `rice=27`, 巫山 `0` crop observations. The source farm type supplies the canonical crop label for imported observation rows; individual `fruit` values remain nullable. Crop type was not inferred from map colors.

## 12. Legacy Map Point Semantics

`location` rows represent ordered farm boundary vertices consumed as a polygon/path. `crops` rows represent georeferenced crop observations/measurements, not fields, plots, or sensors. `farmlocation` is a duplicate/non-authoritative path source for this API and includes one swapped-coordinate row; it is not imported into the canonical point set. No source row was promoted to `farm_fields` without semantic proof.

## 13. Legacy Point Counts

| Farm | Legacy Source Count | Exported | Imported | Deduplicated | Excluded | Final V2 Count |
|---|---:|---:|---:|---:|---:|---:|
| 云阳 | 549 | 549 | 549 SQL rows generated; target not applied | 0 | 0 | Not applied |
| 大足 | 27 | 27 | 27 SQL rows generated; target not applied | 0 | 0 | Not applied |
| 巫山 | 921 | 921 | 921 SQL rows generated; target not applied | 0 | 0 | Not applied |

The normalized source snapshot contains 3 farm rows, 989 boundary rows, and 510 crop rows. Two orphan crop rows are excluded, leaving 1,497 candidate canonical map points.

## 14. Coordinate / CRS Audit

Coordinates are documented as **GCJ-02**, because the legacy UI passes stored values directly to AMap without conversion. No transformation was performed. Source bounding boxes are: 云阳 `lon 108.12910–108.13121, lat 31.16979–31.17153` for boundary rows; crop rows `lon 108.12912–108.13121, lat 31.16977–31.17148`; 大足 crop rows `lon 105.786312–105.786536, lat 29.266753–29.266966`; 巫山 boundary rows `lon 109.561368–110.199858, lat 30.761988–31.475404`. Authoritative `location` and `crops` rows had zero invalid coordinates and zero `(0,0)` rows. The one invalid swapped row was in excluded `farmlocation`.

## 15. Current Commercial V2 Data Path

The V2 path now is frontend → `GET /api/farms/{farmId}/map-data` → `FarmMapDataController` → `FarmMapDataService` → canonical `farm_map_points`, with `FarmFieldService.requireFarm` enforcing existing farm access. The response separates `locations` from `crops` and carries source provenance, owner, CRS, and source attributes. The old `/user/get-farm` endpoint is no longer the formal Dashboard/Overview map source.

## 16. Current Commercial Counts

Before this migration, deployed V2 had no canonical `farm_map_points` table; therefore its canonical map count was effectively `0`. The frontend also contained a demo fixture with 6 crop points and 4 boundary points under demo farm `9001`; those 10 points are not authoritative real-farm counts and are not used by the real canonical path. The target database was not reachable with usable credentials, so no live target count is claimed.

## 17. Root Cause of Reduced Data Coverage

The root cause is the combination `MIGRATION_GAP + CANONICAL_MAPPING_GAP + LEGACY_ADAPTER_MISSING + PILOT_FIXTURE`. V2 migration `V5_3_1__commercial_field_master.sql` explicitly left legacy `location` rows unmigrated, V2 had no canonical map-point entity/API, and the V2 frontend retained demo map data. The legacy API still had the real rows, so this was not proven source deletion and not an admin ownership failure. The fix is the narrow `farm_map_points` canonical extension, idempotent source mapping, farm-specific API, and server-backed frontend adapter.

## 18. Ownership / Access Findings

All three farms exist in the source. Ownership is 云阳→user `1`, 大足→user `1`, 巫山→user `9`; source admin user `2` is expected to see all farms through the existing admin access model. The new map service reuses `FarmFieldService.requireFarm`; it does not disable or bypass ownership checks. Live target access proof remains blocked by target credentials.

## 19. Frontend Hardcode Findings

The real map and Overview paths previously depended on legacy/demo-shaped data; the real paths now request canonical map data. The farm selector is populated from the authenticated farm list and does not hardcode `['云阳','大足','巫山']`. Explicit demo-only views and demo-session adapters remain marked as `DEMO`; they are not used as the real migration source. The browser test asserts the authenticated farm list contains the three restored names when credentials are supplied.

## 20. Canonical Mapping

`farm` → canonical `farm` by source legacy ID, preserving name, type, address, center, zoom, and owner user ID. `location` → `farm_map_points` with `point_kind=BOUNDARY_VERTEX`; `crops` → `farm_map_points` with `point_kind=CROP_OBSERVATION`. Crop observations retain source metrics and nullable fields; no legacy row is forced into `farm_fields`. The new schema stores source system, entity type, legacy ID, sequence, CRS, owner, and status.

## 21. Stable ID Mapping

Farm canonical IDs reuse numeric legacy farm IDs `1`, `7`, and `8`. Boundary point IDs are `legacy-farmap-location-{legacyId}`; observation IDs are `legacy-farmap-crops-{legacyId}`. The unique key `(source_system, source_entity_type, source_legacy_id)` is the canonical source identity. Display names, array positions, nearest records, and natural-language labels are not identity keys.

## 22. Migration Strategy

The controlled flow is source read-only dump → normalized JSON/TSV → explicit exclusion report → target backup → schema migration → farm upsert → idempotent map-point upsert → source/target reconciliation. The importer is `scripts/migrate-legacy-farmap-map-data.ps1`; `-Apply` requires target credentials through environment variables and sets `MYSQL_PWD` only for the child client process scope.

## 23. Source Snapshot

Raw selected-table dumps and normalized artifacts were created outside Git under `E:\FarMapTools\farmap-data-migration\` and were reused for this closure attempt. The snapshot includes all three farms, all 989 authoritative boundary vertices, all 510 crop rows, and the supporting selected source tables. No user table, password, token, or unrelated full-database dump was included.

## 24. Checksums

`checksums/migration-manifest.json` records source label, database name, export timestamps, row counts, and SHA-256 checksums for each raw and normalized artifact. The closure verification matched all 14 registered artifact checksums. The manifest contains no credentials. The regenerated import SQL and exclusion report are also outside Git; the exclusion report records 2 explicit `MISSING_REQUIRED_RELATION` exclusions.

## 25. Target Backup

**BLOCKED / NOT CREATED.** Target metadata is `localhost`, database `zhgy`, role `FarMap V2 canonical target`. `mysql.txt` was loaded securely in process, but authentication was classified as `ACCESS_DENIED`. Target pre-import inventory was therefore not collected, and the mandatory backup of `farm`, `farm_fields`, and `farm_map_points` was not attempted. No source database was used as a target, and no target write was attempted.

## Local Target Datasource Resolution

- Spring DB connectivity: **FAIL / SPRING_NOT_CONNECTED_TO_ZHGY**. No Java/Spring process was running; port 8080 and database-backed API probes were unreachable.
- Effective target endpoint: no effective Spring datasource was proven. The intended local target is `localhost:3306/zhgy`.
- Credential source type: `mysql.txt` was readable and loaded securely; the root `.env` contains only AI API aliases, and no other verified working datasource source was found.
- `mysql.txt` readable: **true**.
- `mysql.txt` username matches working datasource: **unavailable**.
- `mysql.txt` password matches working datasource: **unavailable**.
- Authentication root cause: local MySQL authentication returned **ACCESS_DENIED**; there was no live Spring datasource with which to compare credentials.
- Authentication resolution: **LOCAL_MYSQL_VALID_CREDENTIAL_REQUIRED**. No password reset, grant change, auth-plugin change, or source-server fallback was performed.

## 26. Idempotency

Farm import uses `INSERT ... ON DUPLICATE KEY UPDATE` keyed by the legacy farm ID. Map-point import uses stable point IDs plus the unique source identity key and `ON DUPLICATE KEY UPDATE`. Re-running the generated SQL therefore updates the same canonical rows instead of creating duplicates. This is implemented and script-generated; a live double-run could not be executed without target access.

## 27. Deduplication

No canonical deduplication was applied to the authoritative `location` or `crops` sets because their source IDs are distinct. The duplicate `farmlocation` path was excluded as a source-path duplicate rather than inserted as another boundary set. The one swapped-coordinate `farmlocation` row was not allowed into the canonical set.

## 28. Excluded Records

Legacy crop IDs `484` and `485` reference missing source farm ID `2`; both are excluded with reason `MISSING_REQUIRED_RELATION`. The `farmlocation` table is excluded with `DUPLICATE_SOURCE_PATH` because it is not the authoritative `/user/get-farm` path and contains one invalid swapped-coordinate row. No silent drops were made.

## 29. Migration Execution

Read-only source export and normalization passed, and the existing snapshot passed integrity verification. Deterministic SQL generation passed with `includedFarms=3`, `includedBoundaryVertices=989`, `includedCropObservations=508`, and `excluded=2`. `mysql.txt` was loaded securely for the local target attempt, but `ACCESS_DENIED` occurred before target inventory/backup. Import Run 1, target schema application, and SQL execution were not run.

## 30. Source vs Target Reconciliation

Source and normalized counts reconcile exactly: 云阳 `68+481=549`, 大足 `0+27=27`, and 巫山 `921+0=921`. Generated SQL contains the same per-farm counts, with 2 orphan crop exclusions explicitly accounted for. Target pre-import, post-import, second-run, missing-ID, extra-ID, and duplicate-identity values are `null` in the safe reconciliation artifact because `mysql.txt` authentication failed with `ACCESS_DENIED` before inventory/backup. This remains the primary release blocker.

## 31. Geo Validation

All 989 authoritative boundary coordinates and 510 crop coordinates passed numeric range checks; none was `(0,0)`. Per-farm bounding boxes are in the expected Yunyang, Dazu, and Wushan regions. No longitude/latitude swap or GCJ-02 double conversion was introduced. The excluded `farmlocation` bad row was `(longitude=31.16979, latitude=108.12978)` and was not imported.

## 32. Multi-Farm Validation

The normalized snapshot and generated import cover all three real farms with distinct source IDs, ownership, crop types, and counts. The backend API requires a farm ID and queries only that farm’s canonical points. Deterministic mapping tests passed. Live farm-switch and target count validation remain pending target import and authorized browser login.

## 33. Crop Generalization Validation

Production map state now uses a generic string farm type and maps crop labels from canonical response data. `citrus` remains valid for 云阳, while `rice` and `plum` are preserved for 大足 and 巫山. No global citrus-only assumption was added. The existing demo-only citrus examples remain confined to explicit demo paths.

## 34. Farm Switch API Validation

The farm switch path requests `/api/farms/{selectedFarmId}/map-data`, replaces the store atomically, updates AI page/farm context, and derives visible modes from the returned farm data. The Playwright test waits for each farm’s canonical API response and checks returned `farmId`, `sourceMode=MYSQL`, nonzero point count, and crop labels. Live execution is blocked by missing authorized application credentials and unavailable target data; no live API count is claimed.

## 35. Browser Validation

`LIVE_BROWSER_CONDITIONAL`: Playwright started safely but stopped before login with `Missing FARMAP_PILOT_USERNAME`; no credentials were fabricated or embedded. Consequently the required live checks for selecting 云阳/大足/巫山, full point counts, stale-point clearing, 5xx, and console errors remain pending. This conditional browser result cannot upgrade the core decision because target import/reconciliation is also incomplete.

## 36. Copilot Context Validation

The map path updates AI context with the selected canonical farm ID/name and preserves the existing field-context access boundary. No Diagnosis ID, Recommendation ID, Evidence, Approval, Operations, or Memory contract was changed by this migration. Live multi-farm Copilot context proof is pending target import; no cross-farm memory behavior was altered.

## 37. REAL / DEMO Boundary

Legacy records are represented with `source_system=LEGACY_FARMAP` and are not assigned to `a12-demo`, demo evidence, demo diagnosis, or demo farm identity. Real sessions use canonical API responses; demo sessions remain explicitly `DEMO`. Migration artifacts contain no demo remapping. Evidence is sufficient for design-time REAL Demo Leakage `0`; live target proof remains pending.

## 38. Phase 1.6 Regression

Phase 1.6 long-term-memory tests remained green in the full constrained Maven run. No memory boundary or identity semantics were changed. The migration task stops here and does not start Phase 1.7.

## 39. AI Regression

The constrained full Maven run completed with `145 tests, 0 failures, 0 errors, 14 skipped`, including the existing AI Engine Phase 1.1–1.6 evaluation coverage. The known LocalVision/ONNX host-resource test was excluded as previously authorized; its warning is not classified as agent-tool recovery.

## 40. Commercial Regression

Commercial multisource RAG, commercial agent product-loop, and full commercial MVP product-loop evaluation tests remained green in the full run. No commercial contract regression was observed. New map policy and migration-readiness tests also passed.

## 41. Full Maven

PASS: `145 tests, 0 failures, 0 errors, 14 skipped`, using the existing Maven runtime under `E:\farmap-maven-runtime\apache-maven-3.9.11\`. `LocalVisionServiceTest` was excluded only for the known host native-memory limitation.

## 42. Frontend Build

PASS: `npx tsc -b --pretty false`; targeted ESLint for changed map/dashboard/overview/browser files; and `npx vite build --minify=false` (7,174 modules). The Playwright test is present and credential-safe but conditionally blocked before login.

## 43. Source Server Safety Verification

The source containers remained running. No source restart, stop, removal, volume operation, schema mutation, write query, firewall change, reverse-proxy change, or production file overwrite was performed. Only SELECTs, metadata inspection, and read-only dumps were used. Source row counts were not modified by this task.

## 44. Secrets Review

Passed for the task artifacts. `ip.txt`, deployment environment values, server environment values, passwords, tokens, and API keys were not printed, copied into code/docs, or committed. The temporary SSH helper was kept outside the repository and emitted no secret to logs. The result report contains no credential material.

## 45. Files Changed

Phase-relevant repository changes are: `zhgy/zhgy/src/main/resources/db/migration/V5_4_1__canonical_legacy_map_data.sql`; migration verifier updates and tests; `FarmFieldService`; new `service/map` DTO/service/controller/policy/test classes; `scripts/migrate-legacy-farmap-map-data.ps1`; `farmap-frontend-dev/src/features/farm/mapData.ts`; `src/store/farm.ts`; `src/views/dashboard/index.tsx`; `src/features/overview/adapter.ts`; `e2e/commercial-pilot.spec.ts`; and this result document. All pre-existing dirty worktree changes were preserved.

## 46. Raw Migration Artifact Location

Raw dumps, normalized data, checksums, generated import SQL, exclusion logs, and the reserved backup directory are outside Git at `E:\FarMapTools\farmap-data-migration\`. The repository contains only migration logic, schema, tests, frontend integration, and safe documentation.

## 47. Remaining Gaps

The credentials in `mysql.txt` must be corrected or an approved local target authentication configuration must be supplied. Then target pre-import inventory and backup must be created, schema/import applied, per-farm/source-identity counts reconciled, and the idempotent second run verified. Authorized browser credentials are also required for live farm switching, stale-point, console, and Copilot-context checks. Until those gates pass, V2 must not be declared restored or production-ready.

## 48. Decision

**MANUAL_IMPORT_READY / TARGET_EXECUTION_PENDING —** the original V1 manual package is preserved for audit, and V2 repairs the identified `ERROR 1136` boundary-row column mismatch. The checksum-verified V2 package was generated and statically validated without connecting to or executing against MySQL. No target migration is claimed; do not begin AI Engine Phase 1.7.

## Manual Import Package Status

Generated outside Git under `E:\FarMapTools\farmap-data-migration\manual\`. The original V1 files remain preserved for audit. The corrected V2 files are `farmap-multifarm-manual-import-v2.sql` and `farmap-multifarm-manual-verify-v2.sql`; the existing rollback/readme artifacts remain available. V2 preserves the authoritative V5_4_1 DDL, `BOUNDARY_VERTEX`/`CROP_OBSERVATION` semantics, transactional DML with hard validation and rollback-on-error, and contains no credentials.

The repaired mapping audit passed: before fix, the target list had 31 columns while every boundary row had 33 expressions and every crop row had 31; after fix, all 1,497 point upserts have 31 target columns and 31 expressions. Staging remains `500+500+497`, identity reconciliation is `missing=0, extra=0`, and source snapshot checksums remain unchanged. Target execution remains **USER-PENDING**; no target migration is claimed.
