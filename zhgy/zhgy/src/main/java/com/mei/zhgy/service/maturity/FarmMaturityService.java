package com.mei.zhgy.service.maturity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.ai.*;
import com.mei.zhgy.service.camera.*;
import com.mei.zhgy.service.field.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import com.mei.zhgy.service.field.FieldAccessException;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Current-image observations only; this is not a whole-field maturity measurement. */
@Service
public class FarmMaturityService {
    private static final int MAX_CAMERAS = Integer.MAX_VALUE;
    private static final long TIMEOUT_SECONDS = 90;
    private static final Semaphore SCAN_CAPACITY = new Semaphore(2);
    private final FarmFieldService fields;
    private final FarmCameraService cameras;
    private final CameraSnapshotProvider snapshots;
    private final ModelGateway gateway;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Value("${farmap.presentation-data.enabled:false}")
    private boolean presentationDataEnabled;
    void setPresentationDataEnabled(boolean enabled) { this.presentationDataEnabled = enabled; }
    private JdbcTemplate jdbc;
    private CameraBindingService bindingService;
    @Autowired public void setBindingService(CameraBindingService bindingService) { this.bindingService = bindingService; }
    private volatile boolean runSchemaReady;
    @Autowired public void setJdbc(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public FarmMaturityService(FarmFieldService fields, FarmCameraService cameras, CameraSnapshotProvider snapshots, ModelGateway gateway, ObjectMapper json) {
        this.fields = fields; this.cameras = cameras; this.snapshots = snapshots; this.gateway = gateway; this.json = json;
    }

    public Report scan(Integer farmId, Long actorId) {
        var farm = fields.requireFarm(farmId, actorId);
        if (!SCAN_CAPACITY.tryAcquire()) throw new IllegalStateException("当前扫描任务较多，请稍后重试");
        try {
        List<FarmField> allFields = fields.list(farmId, actorId);
        List<FarmCamera> allCameras = cameras.list(farmId, actorId).stream().sorted(Comparator.comparing(FarmCamera::cameraId)).toList();
        ExecutorService executor = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "maturity-scan"); t.setDaemon(true); return t; });
        Map<String, Future<Observation>> pending = new LinkedHashMap<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        for (FarmCamera camera : selectCameras(allFields, allCameras)) {
            String crop = allFields.stream().filter(f -> Objects.equals(f.getFieldId(), camera.fieldId()) && (f.getSourceMode() == null || !f.getSourceMode().startsWith("SYNTHETIC")) && !"CURATED_PRESENTATION_TOPOLOGY".equals(camera.bindingMethod())).map(FarmField::getCrop).filter(Objects::nonNull).findFirst().orElse(null);
            pending.put(camera.cameraId(), executor.submit(() -> observe(camera, actorId, crop)));
        }
        Map<String, Observation> results = new LinkedHashMap<>();
        try {
            for (FarmCamera camera : allCameras) {
                Future<Observation> future = pending.get(camera.cameraId());
                if (future == null) { results.put(camera.cameraId(), unknown(camera, "本次采样数量已达上限，未采集", null)); continue; }
                try {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new TimeoutException();
                    results.put(camera.cameraId(), future.get(remaining, TimeUnit.NANOSECONDS));
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); results.put(camera.cameraId(), unknown(camera, "扫描中断", null)); }
                catch (ExecutionException | TimeoutException e) { future.cancel(true); results.put(camera.cameraId(), unknown(camera, "采集或识别超时、失败", null)); }
            }
        } finally { executor.shutdownNow(); }
        List<Map<String,Object>> parcels = new ArrayList<>();
        Set<String> assigned = new HashSet<>();
        for (FarmField field : allFields) {
            boolean synthetic = field.getSourceMode() != null && field.getSourceMode().startsWith("SYNTHETIC");
            List<Observation> obs = new ArrayList<>();
            if (!synthetic) for (FarmCamera camera : allCameras) {
                if (Objects.equals(field.getFieldId(), camera.fieldId()) && !"CURATED_PRESENTATION_TOPOLOGY".equals(camera.bindingMethod())) {
                    obs.add(results.get(camera.cameraId())); assigned.add(camera.cameraId());
                }
            }
            Stage stage = aggregate(obs);
            String reason = synthetic ? "展示用模拟地块，不能作为真实地块归属" : obs.isEmpty() ? "该地块没有已确认绑定的摄像头" : stage == Stage.UNKNOWN ? "现有画面不足以判断，或部分摄像头未取得有效结果" : "根据有效画面汇总；部分摄像头可能未采集或不可辨，不能代表整个地块";
            parcels.add(map("fieldId", field.getFieldId(), "name", field.getName(), "latitude", field.getLatitude(), "longitude", field.getLongitude(), "sourceMode", field.getSourceMode(), "stage", stage.name(), "reason", reason, "observations", obs.stream().map(Observation::toMap).toList()));
        }
        for (FarmCamera camera : allCameras) if (!assigned.contains(camera.cameraId())) {
            Observation obs = results.get(camera.cameraId());
            parcels.add(map("name", camera.name(), "latitude", camera.latitude(), "longitude", camera.longitude(), "scope", "UNASSIGNED_CAMERA", "stage", obs.stage().name(), "reason", "摄像头未确认真实地块归属；仅表示此画面", "observations", List.of(obs.toMap())));
        }
        long valid = results.values().stream().filter(o -> o.stage() != Stage.UNKNOWN).count();
        String summary = "共 " + allFields.size() + " 个地块、" + allCameras.size() + " 个摄像头；本次有 " + valid + " 个画面可以判断成熟阶段。未采集或无法辨认的画面记为未知，结果仅覆盖镜头所见。";
        return new Report(farm.getName(), Instant.now().toString(), parcels, summary);
        } finally { SCAN_CAPACITY.release(); }
    }

    /** Persist a single immutable observation run; callers must pass only verified upload URLs. */
    public Map<String,Object> analyze(Integer farmId, Long actorId, List<String> cameraIds, String fieldId, List<String> trustedImageUrls, String kind, String mode) {
        return analyze(farmId, actorId, cameraIds, fieldId, trustedImageUrls, kind, mode, ignored -> {});
    }

    public record ScanProgress(int total, int processed, int captured, int valid) {}

    public Map<String,Object> analyze(Integer farmId, Long actorId, List<String> cameraIds, String fieldId, List<String> trustedImageUrls,
                                     String kind, String mode, Consumer<ScanProgress> onProgress) {
        var farm = fields.requireFarm(farmId, actorId);
        String resolvedKind = kind == null ? "MATURITY" : kind.toUpperCase(Locale.ROOT);
        String resolvedMode = mode == null ? "FARM" : mode.toUpperCase(Locale.ROOT);
        if (!Set.of("MATURITY", "PHENOLOGY", "GROWTH").contains(resolvedKind) || !Set.of("FARM", "CAMERA", "FIELD", "IMAGE").contains(resolvedMode))
            throw new FieldAccessException(422, "INVALID_ANALYSIS", "unsupported analysis kind or mode");
        List<FarmField> allFields = fields.list(farmId, actorId);
        List<FarmCamera> allCameras = cameras.list(farmId, actorId);
        if ("FIELD".equals(resolvedMode) && (fieldId == null || allFields.stream().noneMatch(f -> fieldId.equals(f.getFieldId()) && (isRealField(f) || (presentationDataEnabled && "SYNTHETIC_PRESENTATION".equals(f.getSourceMode()))))))
            throw new FieldAccessException(422, "INVALID_FIELD", "field must belong to this farm and be real");
        Set<String> requested = cameraIds == null ? Set.of() : new LinkedHashSet<>(cameraIds);
        if ("CAMERA".equals(resolvedMode) && requested.isEmpty()) throw new FieldAccessException(422, "CAMERA_REQUIRED", "select at least one camera");
        if (!requested.isEmpty() && allCameras.stream().filter(c -> requested.contains(c.cameraId())).count() != requested.size())
            throw new FieldAccessException(404, "CAMERA_NOT_FOUND", "camera not found in farm");
        if ("IMAGE".equals(resolvedMode) && (trustedImageUrls == null || trustedImageUrls.isEmpty())) throw new FieldAccessException(422, "IMAGE_REQUIRED", "image required");
        if (!SCAN_CAPACITY.tryAcquire()) throw new IllegalStateException("当前扫描任务较多，请稍后重试");
        try {
            Map<String,Map<String,Object>> confirmedBindings = new HashMap<>();
            if (bindingService != null) for (Map<String,Object> b : bindingService.list(farmId, actorId)) confirmedBindings.put(String.valueOf(b.get("cameraId")), b);
            List<FarmCamera> selected = allCameras.stream().filter(c -> switch (resolvedMode) {
                case "CAMERA" -> requested.contains(c.cameraId());
                case "FIELD" -> Objects.equals(fieldId, c.fieldId()) && (!"CURATED_PRESENTATION_TOPOLOGY".equals(c.bindingMethod()) || presentationDataEnabled);
                case "IMAGE" -> false;
                default -> true;
            }).toList();
            int total = selected.size() + ("IMAGE".equals(resolvedMode) ? trustedImageUrls.size() : 0);
            int processed = 0;
            int captured = 0;
            int valid = 0;
            onProgress.accept(new ScanProgress(total, processed, captured, valid));
            ExecutorService executor = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "analysis-run"); t.setDaemon(true); return t; });
            Map<String,Future<Map<String,Object>>> pending = new LinkedHashMap<>();
            for (FarmCamera camera : selected) {
                Map<String,Object> binding = confirmedBindings.get(camera.cameraId());
                boolean validBinding = binding != null && Objects.equals(camera.fieldId(), binding.get("fieldId")) && allFields.stream().anyMatch(f -> Objects.equals(f.getFieldId(), camera.fieldId()) && isRealField(f));
                boolean demoBinding = allFields.stream().anyMatch(f -> isDemoLink(farmId, f, camera));
                if (validBinding || demoBinding) pending.put(camera.cameraId(), executor.submit(() -> observeKind(camera, actorId, resolvedKind)));
            }
            Map<String,Map<String,Object>> observations = new LinkedHashMap<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            try {
                for (FarmCamera camera : selected) {
                    Future<Map<String,Object>> observationTask = pending.get(camera.cameraId());
                    Map<String,Object> observation;
                    if (observationTask == null) {
                        observation = unavailable(camera, "未确认真实地块及监测区绑定，未采集");
                        observation.put("status", "UNBOUND");
                    } else {
                        try {
                            long remaining = deadline - System.nanoTime();
                            if (remaining <= 0) throw new TimeoutException();
                            observation = observationTask.get(remaining, TimeUnit.NANOSECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            observation = unavailable(camera, "采集中断");
                        } catch (ExecutionException | TimeoutException e) {
                            observationTask.cancel(true);
                            observation = unavailable(camera, "采集或识别超时、失败");
                        }
                    }
                    observations.put(camera.cameraId(), observation);
                    if (observation.get("imageUrl") != null) captured++;
                    if ("SUCCESS".equals(observation.get("status"))) valid++;
                    onProgress.accept(new ScanProgress(total, ++processed, captured, valid));
                }
            } finally { executor.shutdownNow(); }
            for (Map<String,Object> binding : confirmedBindings.values()) {
                Map<String,Object> obs = observations.get(String.valueOf(binding.get("cameraId")));
                if (obs != null) { obs.put("areaId", binding.get("areaId")); obs.put("areaName", binding.get("areaName")); }
            }
            List<Map<String,Object>> parcels = new ArrayList<>();
            Set<String> assigned = new HashSet<>();
            boolean hasDemoTopology = false;
            for (FarmField field : allFields) {
                if ("FIELD".equals(resolvedMode) && !field.getFieldId().equals(fieldId)) continue;
                List<Map<String,Object>> obs = new ArrayList<>();
                boolean demoGroup = false;
                for (FarmCamera camera : selected) {
                    boolean demo = isDemoLink(farmId, field, camera);
                    boolean confirmed = isRealField(field) && confirmedBindings.containsKey(camera.cameraId()) && Objects.equals(field.getFieldId(), camera.fieldId()) && !"CURATED_PRESENTATION_TOPOLOGY".equals(camera.bindingMethod());
                    if (demo || confirmed) {
                        Map<String,Object> observation = observations.get(camera.cameraId());
                        if (demo) { observation.put("topology", "DEMO"); observation.put("areaId", "demo:" + field.getFieldId()); observation.put("areaName", field.getName()); observation.put("bindingSource", "CURATED_PRESENTATION_TOPOLOGY"); demoGroup = true; }
                        obs.add(observation); assigned.add(camera.cameraId());
                    }
                }
                if (demoGroup) hasDemoTopology = true;
                if (!obs.isEmpty() || "FIELD".equals(resolvedMode) || "FARM".equals(resolvedMode)) parcels.add(map("fieldId", field.getFieldId(), "name", field.getName(), "latitude", field.getLatitude(), "longitude", field.getLongitude(), "sourceMode", field.getSourceMode(), "scope", demoGroup ? "DEMO_MONITORING_REGION" : null, "topology", demoGroup ? "DEMO" : null, "bindingSource", demoGroup ? "CURATED_PRESENTATION_TOPOLOGY" : null, "stage", aggregateMaps(obs), "reason", demoGroup ? "演示监测分组；分析仅基于实际摄像头画面，不能代表整个地块" : obs.isEmpty() ? "该地块没有已确认绑定的摄像头" : "根据有效画面汇总，仅代表镜头所见", "observations", obs));
            }
            for (FarmCamera camera : selected) if (!assigned.contains(camera.cameraId())) { Map<String,Object> obs = observations.get(camera.cameraId()); parcels.add(map("name", camera.name(), "latitude", camera.latitude(), "longitude", camera.longitude(), "scope", "UNASSIGNED_CAMERA", "stage", obs.get("stage"), "reason", "摄像头未确认真实地块归属；仅表示此画面", "observations", List.of(obs))); }
            if ("IMAGE".equals(resolvedMode)) for (String url : trustedImageUrls) {
                Map<String,Object> obs = observeImage(url, resolvedKind);
                parcels.add(map("name", "上传图片", "scope", "IMAGE", "stage", obs.get("stage"), "reason", "仅代表上传画面", "observations", List.of(obs)));
                if (obs.get("imageUrl") != null) captured++;
                if ("SUCCESS".equals(obs.get("status"))) valid++;
                onProgress.accept(new ScanProgress(total, ++processed, captured, valid));
            }
            String summary = "巡检 " + total + " 个画面来源，取得 " + captured + " 张画面，有效识别 " + valid + " 条。";
            if (valid > 0) summary += "结论仅代表画面可见范围。";
            else if (observations.values().stream().anyMatch(value -> "CAPTURE_NOT_CONFIGURED".equals(value.get("status")))) {
                summary += "云眸自动抓图未配置，当前没有可用于识别的画面。可在生产监测中上传对应摄像头的截图后重试。";
            } else summary += "当前无有效证据，无法判断。";
            Map<String,Object> report = map("runId", UUID.randomUUID().toString(), "farmId", farmId, "farmName", farm.getName(), "mode", resolvedMode, "kind", resolvedKind, "observedAt", Instant.now().toString(), "parcels", parcels, "summary", summary, "status", valid > 0 ? "COMPLETED" : "NO_VALID_OBSERVATIONS");
            if (hasDemoTopology) report.put("topology", "DEMO");
            boolean harvest = parcels.stream().flatMap(p -> ((List<?>) p.get("observations")).stream()).map(o -> (Map<?,?>)o).anyMatch(o -> "SUCCESS".equals(o.get("status")) && Set.of("RIPE", "OVER_RIPE").contains(o.get("stage")));
            report.put("recommendations", harvest && "MATURITY".equals(resolvedKind) ? List.of("建议实地复核可见成熟或过熟果实，结合品种与采收标准安排采收。") : List.of());
            saveRun(report, actorId); return report;
        } finally { SCAN_CAPACITY.release(); }
    }

    private static boolean isRealField(FarmField field) { return field.getSourceMode() == null || !field.getSourceMode().startsWith("SYNTHETIC"); }
    private boolean isDemoLink(Integer farmId, FarmField field, FarmCamera camera) {
        return presentationDataEnabled && Objects.equals(camera.farmId(), farmId) && Objects.equals(field.getFarmId(), farmId)
                && "SYNTHETIC_PRESENTATION".equals(field.getSourceMode())
                && "CURATED_PRESENTATION_TOPOLOGY".equals(camera.bindingMethod())
                && Objects.equals(field.getFieldId(), camera.fieldId());
    }
    private Map<String,Object> unavailable(FarmCamera camera, String reason) { Map<String,Object> result = unknown(camera, reason, null).toMap(); result.put("status", "UNAVAILABLE"); result.put("quality", "UNKNOWN"); return result; }
    private String aggregateMaps(List<Map<String,Object>> observations) {
        Set<String> stages = new HashSet<>(); for (Map<String,Object> o : observations) { String stage = String.valueOf(o.getOrDefault("stage", "UNKNOWN")); if (!"UNKNOWN".equals(stage)) stages.add(stage); }
        return stages.isEmpty() ? "UNKNOWN" : stages.size() == 1 ? stages.iterator().next() : "MIXED";
    }
    private Map<String,Object> observeKind(FarmCamera camera, Long actorId, String kind) {
        try { CameraSnapshotProvider.Snapshot shot = snapshots.capture(camera.cameraId(), actorId); Map<String,Object> result = interpretImage(shot, kind); result.put("cameraId", camera.cameraId()); result.put("cameraName", camera.name()); return result; }
        catch (CameraSnapshotProvider.CameraOfflineException e) { Map<String,Object> result = unavailable(camera, "设备明确离线，未采集"); result.put("status", "OFFLINE"); return result; }
        catch (CameraSnapshotProvider.CaptureNotConfiguredException e) {
            Map<String,Object> result = unavailable(camera, "未配置自动抓图，暂无该摄像头的近期截图");
            result.put("status", "CAPTURE_NOT_CONFIGURED");
            return result;
        }
        catch (Exception e) { Map<String,Object> result = unknown(camera, "当前画面采集或视觉识别失败", null).toMap(); result.put("status", "UNAVAILABLE"); result.put("quality", "UNKNOWN"); return result; }
    }
    private Map<String,Object> observeImage(String url, String kind) { Map<String,Object> result = interpretImage(new CameraSnapshotProvider.Snapshot(url, Instant.now().toString(), "USER_UPLOAD"), kind); result.put("cameraName", "上传图片"); return result; }
    private Map<String,Object> interpretImage(CameraSnapshotProvider.Snapshot shot, String kind) {
        Map<String,Object> fallback = map("stage", "UNKNOWN", "label", "UNKNOWN", "reason", "视觉识别不可用或画面不足以判断", "status", "UNAVAILABLE", "quality", "UNKNOWN", "imageUrl", shot.imageUrl(), "capturedAt", shot.capturedAt(), "source", shot.source());
        try {
            String instruction = switch (kind) {
                case "PHENOLOGY" -> "识别照片中可见植物的物候阶段，如萌芽、开花、坐果、果实发育或休眠；阶段不清楚时返回 UNKNOWN。";
                case "GROWTH" -> "评估单张照片中植株可见的长势外观。这不是物候阶段识别，无需花或果实。仅基于可见的叶色、枝条、冠层完整度以及明显萎蔫或稀疏判断。stage 只能为 GOOD（外观较旺盛）、FAIR（外观一般）、WEAK（外观偏弱）或 UNKNOWN（证据不足）。不得从单张图片推断生长速度、缺素或病因。";
                default -> "根据可见果实外观判断成熟阶段，stage 只能为 UNRIPE、TURNING、RIPE、OVER_RIPE、MIXED 或 UNKNOWN；没有清晰果实时必须 UNKNOWN。";
            };
            ModelCallResult result = gateway.complete(ModelCallRequest.builder().taskType(AiTaskType.IMAGE_UNDERSTANDING).requestId(UUID.randomUUID().toString()).structuredOutput(true).imageUrls(List.of(shot.imageUrl())).systemPrompt(instruction + " 返回 JSON：{\"stage\":\"阶段\",\"reason\":\"画面证据\",\"subjectVisible\":true,\"imageClear\":true,\"fruitVisible\":true}；仅在主体清晰可辨时将对应布尔值设为 true。不得推测画面之外、给出百分比或编造观察。") .userPrompt("仅分析提供的图片；无法可靠判断时返回 UNKNOWN。") .context(Map.of("imageCount", 1, "maxOutputTokens", 400)).build());
            if (result == null || !result.isSuccess() || result.getContent() == null) return fallback;
            if (result.getSelectedModel() != null) { String model = result.getSelectedModel().toLowerCase(Locale.ROOT); if (!model.equals("deepseek-v4.1-flash") && !model.startsWith("qwen3-vl-")) return fallback; }
            JsonNode node = json.readTree(result.getContent().trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", ""));
            String stage = node.path("stage").asText("UNKNOWN").trim(), reason = node.path("reason").asText("").trim();
            if (stage.isBlank() || stage.length() > 80 || reason.isBlank() || reason.length() > 500 || reason.matches(".*[0-9０-９]+\\s*[%％].*")) return fallback;
            if ("MATURITY".equals(kind) && !Set.of("UNRIPE", "TURNING", "RIPE", "OVER_RIPE", "MIXED", "UNKNOWN").contains(stage)) return fallback;
            if ("GROWTH".equals(kind) && !Set.of("GOOD", "FAIR", "WEAK", "UNKNOWN").contains(stage)) return fallback;
            if (!"UNKNOWN".equals(stage) && (!node.path("subjectVisible").isBoolean() || !node.path("subjectVisible").booleanValue() || !node.path("imageClear").isBoolean() || !node.path("imageClear").booleanValue() || ("MATURITY".equals(kind) && (!node.path("fruitVisible").isBoolean() || !node.path("fruitVisible").booleanValue())))) return fallback;
            String label = switch (stage) { case "GOOD" -> "外观较旺盛"; case "FAIR" -> "外观一般"; case "WEAK" -> "外观偏弱"; default -> stage; };
            return map("stage", stage, "label", label, "reason", reason, "status", "UNKNOWN".equals(stage) ? "UNKNOWN" : "SUCCESS", "quality", "UNKNOWN".equals(stage) ? "INSUFFICIENT" : "USABLE", "imageUrl", shot.imageUrl(), "capturedAt", shot.capturedAt(), "source", shot.source());
        } catch (Exception e) { return fallback; }
    }
    private synchronized void ensureRunSchema() {
        if (runSchemaReady) return;
        jdbc.execute("CREATE TABLE IF NOT EXISTS maturity_analysis_runs (run_id VARCHAR(36) PRIMARY KEY, farm_id INT NOT NULL, owner_id BIGINT NOT NULL, observed_at VARCHAR(40) NOT NULL, report_json LONGTEXT NOT NULL, KEY idx_maturity_farm_owner (farm_id,owner_id,observed_at))"); runSchemaReady = true;
    }
    private void saveRun(Map<String,Object> report, Long actorId) {
        ensureRunSchema(); try { jdbc.update("INSERT INTO maturity_analysis_runs(run_id,farm_id,owner_id,observed_at,report_json) VALUES (?,?,?,?,?)", report.get("runId"), report.get("farmId"), actorId, report.get("observedAt"), json.writeValueAsString(report)); } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("无法保存分析结果", e); }
    }
    public List<Map<String,Object>> runs(Integer farmId, Long actorId) { fields.requireFarm(farmId, actorId); ensureRunSchema(); return jdbc.query("SELECT report_json FROM maturity_analysis_runs WHERE farm_id=? AND owner_id=? ORDER BY observed_at DESC LIMIT 100", (rs,n) -> readReport(rs.getString(1)), farmId, actorId); }
    public Map<String,Object> latest(Integer farmId, Long actorId) { List<Map<String,Object>> reports = runs(farmId, actorId); if (reports.isEmpty()) throw new FieldAccessException(404, "RUN_NOT_FOUND", "no analysis run found"); return reports.get(0); }
    public Map<String,Object> run(String runId, Long actorId) { ensureRunSchema(); List<Map<String,Object>> reports = jdbc.query("SELECT report_json FROM maturity_analysis_runs WHERE run_id=? AND owner_id=?", (rs,n) -> readReport(rs.getString(1)), runId, actorId); if (reports.isEmpty()) throw new FieldAccessException(404, "RUN_NOT_FOUND", "analysis run not found"); Map<String,Object> report = reports.get(0); fields.requireFarm(((Number)report.get("farmId")).intValue(), actorId); return report; }
    private Map<String,Object> readReport(String value) { try { return json.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {}); } catch (Exception e) { throw new IllegalStateException("无法读取分析结果", e); } }

    private List<FarmCamera> selectCameras(List<FarmField> fields, List<FarmCamera> cameras) {
        List<Queue<FarmCamera>> groups = new ArrayList<>();
        Set<String> grouped = new HashSet<>();
        for (FarmField field : fields) {
            if (field.getSourceMode() != null && field.getSourceMode().startsWith("SYNTHETIC")) continue;
            Queue<FarmCamera> group = new ArrayDeque<>();
            for (FarmCamera camera : cameras) if (Objects.equals(field.getFieldId(), camera.fieldId()) && !"CURATED_PRESENTATION_TOPOLOGY".equals(camera.bindingMethod())) { group.add(camera); grouped.add(camera.cameraId()); }
            if (!group.isEmpty()) groups.add(group);
        }
        Queue<FarmCamera> unassigned = new ArrayDeque<>();
        for (FarmCamera camera : cameras) if (!grouped.contains(camera.cameraId())) unassigned.add(camera);
        if (!unassigned.isEmpty()) groups.add(unassigned);
        List<FarmCamera> selected = new ArrayList<>();
        for (Queue<FarmCamera> group : groups) {
            if (selected.size() == MAX_CAMERAS) break;
            FarmCamera available = group.stream().filter(snapshots::hasRecentBrowserFrame).findFirst().orElse(null);
            if (available != null) { selected.add(available); group.remove(available); }
        }
        for (Queue<FarmCamera> group : groups) {
            if (selected.size() == MAX_CAMERAS) break;
            for (Iterator<FarmCamera> iterator = group.iterator(); iterator.hasNext() && selected.size() < MAX_CAMERAS; ) {
                FarmCamera camera = iterator.next();
                if (snapshots.hasRecentBrowserFrame(camera)) { selected.add(camera); iterator.remove(); }
            }
        }
        while (selected.size() < MAX_CAMERAS) {
            boolean added = false;
            for (Queue<FarmCamera> group : groups) if (!group.isEmpty() && selected.size() < MAX_CAMERAS) { selected.add(group.remove()); added = true; }
            if (!added) break;
        }
        return selected;
    }

    private Observation observe(FarmCamera camera, Long actorId, String crop) {
        CameraSnapshotProvider.Snapshot shot = null;
        try {
            shot = snapshots.capture(camera.cameraId(), actorId);
            if (shot == null || shot.imageUrl() == null || shot.imageUrl().isBlank()) return unknown(camera, "未取得当前画面", shot);
            ModelCallResult result = gateway.complete(ModelCallRequest.builder().taskType(AiTaskType.IMAGE_UNDERSTANDING)
                    .requestId(UUID.randomUUID().toString()).structuredOutput(true).imageUrls(List.of(shot.imageUrl()))
                    .systemPrompt("仅根据提供的照片评估可见果实的成熟外观。返回 JSON：{\"fruitVisible\":true,\"stage\":\"UNRIPE|TURNING|RIPE|MIXED|UNKNOWN\",\"reason\":\"可见证据\"}。没有清晰果实时 fruitVisible 必须为 false 且 stage 必须为 UNKNOWN；遮挡、作物或成熟度不明时返回 UNKNOWN。多种明显阶段同框为 MIXED。不得猜测画面外情况、化学指标或百分比。")
                    .userPrompt("描述可见的果实轮廓、果皮色泽和发育特征。色泽可能受品种与光照影响，不要根据单一颜色判断是否可采。无法可靠判断时返回 UNKNOWN。" + (crop == null ? "" : "地块登记作物仅供参考：" + crop))
                    .context(Map.of("imageCount", 1, "maxOutputTokens", 400)).build());
            return evaluate(camera, shot, result);
        } catch (Exception e) {
            String reason = shot == null && !snapshots.remoteCaptureConfigured()
                    ? "未配置自动抓拍，且没有可用的近期监控截图"
                    : "当前画面采集或视觉识别失败";
            return unknown(camera, reason, shot);
        }
    }

    /** Model interpretation boundary, replaceable by a future detector. */
    Observation evaluate(FarmCamera camera, CameraSnapshotProvider.Snapshot shot, ModelCallResult result) {
        try {
            if (result == null || !result.isSuccess() || result.getContent() == null) return unknown(camera, "视觉识别不可用", shot);
            if (result.getSelectedModel() != null) {
                String model = result.getSelectedModel().toLowerCase(Locale.ROOT);
                if (!model.equals("deepseek-v4.1-flash") && !model.startsWith("qwen3-vl-")) return unknown(camera, "未确认当前模型具备图片识别能力", shot);
            }
            String text = result.getContent().trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            JsonNode node = json.readTree(text);
            Stage stage = Stage.valueOf(node.path("stage").asText("UNKNOWN"));
            String reason = node.path("reason").asText("").trim();
            if (reason.isBlank() || reason.length() > 500 || reason.matches(".*[0-9０-９]+\\s*[%％].*")) return unknown(camera, "视觉证据不足或结果格式不可靠", shot);
            if (!node.path("fruitVisible").isBoolean() || !node.path("fruitVisible").booleanValue()) {
                return unknown(camera, stage == Stage.UNKNOWN ? reason : "画面未确认有清晰可见的果实", shot);
            }
            return new Observation(camera.cameraId(), camera.name(), stage, reason, shot.capturedAt(), shot.imageUrl(), shot.source());
        } catch (Exception e) { return unknown(camera, "视觉识别结果无法解析", shot); }
    }
    private static Stage aggregate(List<Observation> obs) {
        if (obs.isEmpty()) return Stage.UNKNOWN;
        Set<Stage> stages = new HashSet<>(); obs.stream().filter(o -> o.stage() != Stage.UNKNOWN).forEach(o -> stages.add(o.stage()));
        if (stages.isEmpty()) return Stage.UNKNOWN;
        return stages.size() > 1 || stages.contains(Stage.MIXED) ? Stage.MIXED : stages.iterator().next();
    }
    private static Observation unknown(FarmCamera camera, String reason, CameraSnapshotProvider.Snapshot shot) {
        return new Observation(camera.cameraId(), camera.name(), Stage.UNKNOWN, reason, shot == null ? null : shot.capturedAt(), shot == null ? null : shot.imageUrl(), shot == null ? null : shot.source());
    }
    private static Map<String,Object> map(Object... pairs) { Map<String,Object> map = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) if (pairs[i+1] != null) map.put((String)pairs[i], pairs[i+1]); return map; }
    public enum Stage { UNRIPE, TURNING, RIPE, OVER_RIPE, MIXED, UNKNOWN }
    record Observation(String cameraId, String cameraName, Stage stage, String reason, String capturedAt, String imageUrl, String source) {
        Map<String,Object> toMap() { return map("cameraId", cameraId, "cameraName", cameraName, "stage", stage.name(), "reason", reason, "capturedAt", capturedAt, "imageUrl", imageUrl, "source", source); }
    }
    public record Report(String farmName, String observedAt, List<Map<String,Object>> parcels, String summary) {
        public Map<String,Object> toUiData() { return map("farmName", farmName, "observedAt", observedAt, "parcels", parcels, "summary", summary); }
        public boolean hasJudgments() {
            return parcels.stream().anyMatch(parcel -> Stage.valueOf(String.valueOf(parcel.get("stage"))) != Stage.UNKNOWN);
        }
    }
}



