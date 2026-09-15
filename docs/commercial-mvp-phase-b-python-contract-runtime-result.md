# FarMap Commercial MVP Integration — Phase B Result

## 1. Executive Summary

Phase B establishes the first stable Python Intelligence Plane boundary. Spring now has one `IntelligenceGateway` for vision, text embedding, image embedding, health, and capabilities. Python exposes versioned `ai.v1` routes, typed failures, explicit readiness states, service authentication, image reference validation, and request tracing. Existing Flask and Local AI routes remain available for compatibility.

**Code status: GO for the implemented contract. Commercial MVP status: CONDITIONAL GO.** The local BGE/CLIP runtime is verified. Vision is contract-complete but live Hunyuan inference is not run because the current environment has no configured Hunyuan key. Production still depends on a real migration runner, a configured internal service token, and deployment-level object storage allow-listing.

## 2. Phase A input

The Phase A audit identified three independent intelligence paths: Flask `zhgy/app.py` on port 5000 for Hunyuan vision; `local-ai/app.py` on port 8001 for BGE-small-zh-v1.5 and CLIP ONNX embeddings; and Java `ModelGateway → BailianModelClient` for Qwen/Bailian grounded generation. This phase keeps the stable Qwen path and does not redesign RAG or Agent Planner behavior.

## 3. Previous AI runtime graph

Before Phase B, `AIModelServiceImpl` posted directly to the legacy Flask `/analyze`, while `LocalBgeEmbeddingProvider` and `LocalClipEmbeddingProvider` posted directly to Local AI `/embedding/text` and `/embedding/image`. The runtime health endpoint only inspected Local AI. Legacy Flask imported a Hunyuan client at module load and contained an insecure default API key and unrestricted URL fetch behavior.

## 4. Root problems addressed

- No versioned cross-language contract or typed error envelope.
- Multiple Java services knew Python hosts and endpoint paths.
- Flask process health was coupled to Hunyuan key availability.
- Image URLs were not protected against SSRF or size abuse.
- Embedding dimensionality was not validated at the business boundary.
- Runtime health did not show the unified intelligence plane or capabilities.
- Trace/request identity was not echoed across the service boundary.

## 5. Architecture decision

Spring Boot remains the Commercial Control Plane. Python remains an inference-only Intelligence Plane. `ModelGateway` and its Bailian/Qwen integration remain the text-generation path for grounded diagnosis and copilot work. Vision is routed through Python v1; BGE and CLIP remain the existing local models behind the v1 Local AI adapter. No LangChain, vector database, model retraining, RAG redesign, or Agent V2 work was introduced.

## 6. Intelligence Plane boundary

Python returns observations and vectors only. Stable Evidence, Diagnosis, Recommendation, AgentRun, Approval, OperationsTask, Outcome, ExpertReview, and HistoricalCaseCandidate IDs remain Java/database concerns. Python does not write MySQL, Mongo, or Milvus and cannot decide approval or candidate eligibility.

## 7. Java gateway

Added `IntelligenceGateway` and `IntelligenceGatewayException`. The gateway owns v1 URL construction, service authentication, request IDs, trace propagation, response-version validation, 512-dimension checks, typed transport mapping, and readiness aggregation. BGE/CLIP providers use the gateway in Spring; compatibility constructors keep existing unit tests and legacy probes working without bypassing production wiring. `AIModelServiceImpl` now uses the gateway for the commercial vision path and serialises the adapter's legacy-compatible `analysis` payload for the existing persistence contract.

## 8. Python runtime

Flask now provides `/v1/health`, `/v1/capabilities`, and `/v1/vision/analyze`. Hunyuan client construction is lazy, so missing configuration reports `UNAVAILABLE` instead of preventing health checks. Local AI now provides `/v1/health`, `/v1/capabilities`, `/v1/embeddings/text`, and `/v1/embeddings/image`, while preserving old routes. Existing BGE and CLIP assets are reused unchanged.

## 9. ai.v1 contract

The contract is documented in [python-intelligence-contract-v1.md](python-intelligence-contract-v1.md). It defines `VISION_ANALYZE`, `TEXT_EMBED`, and `IMAGE_EMBED`, stable request/trace IDs, explicit statuses, observation-only vision output, vector dimension 512, and a structured error envelope. Python never returns business IDs.

## 10. Vision contract

The Java request contains `images: [{uri}]`, an optional context map, and `task: VISION_ANALYZE`. One to five image references are accepted. Responses include `contractVersion`, `requestId`, `status`, model label, observations, warnings, latency, and a compatibility `analysis` field. A model failure is an error response; it is not an empty diagnosis.

## 11. Text embedding contract

The Local AI adapter accepts a non-empty string array and returns one vector per item, model, latency, `dimension: 512`, and `status: SUCCEEDED`. Java validates the version and dimension before exposing results to retrieval code.

## 12. Image embedding contract

The Local AI adapter accepts a validated base64/data URI and returns a normalised CLIP vector with dimension 512. Java validates the same contract. The existing local CLIP ONNX asset and cache behavior remain intact.

## 13. Health and capabilities

`RuntimeHealthController` now reports `intelligencePlane`, `contractVersion`, `vision`, `textEmbedding`, and `imageEmbedding`, while retaining the existing localAi, historical, knowledge, agent, operations, expert-review, candidate, and migration fields. The Python health model distinguishes process `READY` from individual model readiness.

## 14. Error and availability semantics

Transport, timeout, HTTP, contract, and model-unavailable conditions are mapped to `IntelligenceGatewayException`. `EMPTY` is not used as a substitute for `UNAVAILABLE`. No broad catch converts a failed inference into a successful empty result. Retry policy remains deliberately small: the gateway performs no automatic retry; provider-specific retry behavior remains inside the existing Bailian client.

## 15. Trace and observability

Java sends `requestId` and `traceId` in every v1 body. Python echoes them. Safe metadata includes task, status, model, and latency; credentials and image contents are excluded.

## 16. SSRF and image security

Flask v1 accepts data URIs and explicit allow-listed HTTP(S) object hosts. It rejects `file`, `ftp`, localhost aliases, loopback, link-local, private, reserved IP literals, unlisted hosts, malformed data URIs, empty data, and payloads over 12 MB. The production deployment supplies `FARMAP_ALLOWED_IMAGE_HOSTS` through environment configuration. This is a host allow-list, not an arbitrary URL fetcher.

## 17. Authentication

The shared internal token is supplied through `FARMAP_INTELLIGENCE_SERVICE_TOKEN`. The frontend never receives or sends it. A local-only opt-in allows unauthenticated developer probes when no token is configured; deployment does not use a hard-coded fallback key. Legacy Flask `API_KEYS` now defaults to empty rather than `your-api-key`.

## 18. Legacy compatibility

Legacy `/analyze` and `/embedding/*` remain unchanged in shape for old callers. They are compatibility routes, not the commercial contract. New Java business paths use v1. The old Flask route still accepts only HTTP(S) URLs and X-API-Key, so it is explicitly marked as a migration/deprecation surface in this result; it should be removed or isolated after all callers move to v1.

## 19. Model consolidation

The existing GroundedDiagnosisGenerator → ModelGateway → Bailian/Qwen path remains authoritative for grounded text generation. Hunyuan remains the configured vision provider through the Flask adapter. BGE and CLIP remain local offline models. No direct Java Hunyuan text path was altered because it is outside the Python Intelligence Plane scope.

## 20. Local startup and preflight

Added `scripts/run-commercial-mvp-local.ps1` to start Local AI and Flask vision processes (and optionally the backend) with hidden child windows, `.env` loading, and no secret defaults. Added `scripts/check-commercial-mvp-runtime.ps1` to probe Python v1 and Spring runtime health. The scripts report process IDs and statuses without printing credentials.

## 21. Runtime verification

Using the existing `D:\anaconda\envs\llm\python.exe`, Local AI started successfully. `/v1/health` reported `READY`; both BGE and CLIP reported `READY` and dimension 512. `/v1/capabilities` listed `TEXT_EMBED` and `IMAGE_EMBED`. Real text and 1-pixel image v1 embedding requests returned `SUCCEEDED` with dimension 512. Flask started successfully and `/v1/health` reported `DEGRADED` with process `READY` and vision `UNAVAILABLE` because no Hunyuan key is configured.

## 22. Contract tests

- Flask `zhgy/test_intelligence_contract.py`: 4 tests passed, including versioned health, SSRF/protocol rejection, image-count validation, authentication, and explicit model-unavailable behavior.
- Local AI `local-ai/test_contract.py`: 1 contract validation test passed. It avoids the incompatible Starlette/httpx TestClient in the current environment and exercises the endpoint functions directly.
- Java `IntelligenceGatewayContractTest`: 3 tests passed, covering vision version mapping, embedding mapping, invalid dimension, unavailable response, and timeout semantics.
- Existing Local AI client/provider tests: 3 tests passed.

## 23. Spring→Python E2E

With Local AI running on port 8001 and `FARMAP_RUN_LOCAL_AI_INTEGRATION=true`, `LocalAiRuntimeIntegrationTest` passed. It exercised the old client probes and the production-mode `IntelligenceGateway` for BGE and CLIP v1 calls. No business database writes were performed. A live Hunyuan vision E2E was not run because the provider key is not configured; the v1 contract and unavailable path were verified.

## 24. Product-loop regression

The product-loop unit regressions reached by the full Maven run passed. MySQL/integration tests remain environment-gated and skipped. A final full-suite rerun was blocked by the pre-existing `LocalVisionServiceTest` native Surefire fork crash (`Corrupted channel by directly writing to native stream` / forked VM terminated), so the skipped MySQL/HTTP matrix and a clean full-suite signal remain release gates.

## 25. AI baseline and Phase 1.1 regression

`AiEngineBaselineEvaluationTest` and `AgentV2Phase11EvaluationTest` both passed before the pre-existing `LocalVisionServiceTest` fork crash terminated the final full-suite run. No Agent Planner, Evidence Sufficiency, or RAG implementation was changed.

## 26. Backend validation

`mvn -DskipTests compile` passed. Targeted Java AI tests passed (3 gateway contract tests plus the existing Local AI client/provider tests); Python contract tests passed (Flask 4/4 and Local AI 1/1). The final `mvn test` run is **BLOCKED** by the pre-existing `LocalVisionServiceTest` Surefire fork/native-stream issue, with no assertion failures reported in the executed tests.

## 27. Frontend

No frontend code was changed in Phase B. Frontend requests remain routed to Spring APIs. The v1 token and Python endpoints are server-side concerns.

## 28. Files added or modified

Added:

- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/IntelligenceGateway.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/IntelligenceGatewayException.java`
- `zhgy/zhgy/src/test/java/com/mei/zhgy/service/ai/IntelligenceGatewayContractTest.java`
- `zhgy/test_intelligence_contract.py`
- `local-ai/test_contract.py`
- `scripts/run-commercial-mvp-local.ps1`
- `scripts/check-commercial-mvp-runtime.ps1`
- `docs/python-intelligence-contract-v1.md`

Modified for the boundary:

- `zhgy/app.py`
- `zhgy/hunyuan_vision.py`
- `local-ai/app.py`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/LocalAiClient.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/LocalBgeEmbeddingProvider.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/ai/LocalClipEmbeddingProvider.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/service/impl/AIModelServiceImpl.java`
- `zhgy/zhgy/src/main/java/com/mei/zhgy/controller/RuntimeHealthController.java`
- `zhgy/zhgy/src/main/resources/application.yml`
- `zhgy/zhgy/src/test/java/com/mei/zhgy/service/ai/LocalAiRuntimeIntegrationTest.java`
- `deployment/docker-compose.yml`

The worktree also contains earlier user-approved Phase A, backend-loop, and frontend files; they were preserved and not reset.

## 29. Limitations and risks

- Hunyuan live inference is not available until `HUNYUAN_API_KEY` is configured and the provider is reachable.
- Existing legacy `/analyze` still has broader URL semantics and should be retired after migration; v1 is the safe commercial path.
- A host allow-list does not replace a production object-storage proxy with content-type and response-size enforcement.
- Spring runtime health can report Local AI unavailable when the process is down; it cannot make an unavailable model ready.
- The project still lacks a formal migration runner, so deployment schema readiness remains conditional.

## 30. Release gates

Before production promotion, configure a non-empty shared service token in Java, Flask, and Local AI; configure an explicit object host allow-list; run the full Maven suite and 5.2.1–5.2.4 MySQL/HTTP regression matrix; run the AI baseline and Phase 1.1 evaluation suites; and execute a live Hunyuan contract call in a controlled environment. No vector-store or business-database mutation is required for these contract checks.

## 31. GO / NO-GO

**Phase B code: GO.** The versioned contract, gateway, health semantics, SSRF/auth gates, 512-dimension validation, local runtime, and targeted tests are in place. Full Maven remains blocked only by the pre-existing `LocalVisionServiceTest` fork issue.

**Commercial MVP production: CONDITIONAL GO.** It is not a full production release until the service token, Hunyuan runtime, object-storage boundary, migration runner, and complete regression matrix are confirmed. The next authorized scope is **Phase C — Farm / Field / Real Context & Frontend Real-First Wiring**.
