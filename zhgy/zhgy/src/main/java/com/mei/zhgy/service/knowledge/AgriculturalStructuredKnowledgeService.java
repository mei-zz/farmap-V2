package com.mei.zhgy.service.knowledge;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.service.agriculture.CropResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import javax.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Relational facts for questions whose answer is a known stage/signal rule.
 * Explanatory questions are deliberately marked non-deterministic so the
 * normal RAG path can add context instead of being replaced by one row.
 */
@Slf4j
@Component
public class AgriculturalStructuredKnowledgeService {
    static final String SOURCE_REF = "curated://agri-stage-knowledge-v1";

    private final DataSource dataSource;
    private final CropResolver cropResolver;
    private volatile boolean schemaChecked;

    @Autowired
    public AgriculturalStructuredKnowledgeService(@Autowired(required = false) DataSource dataSource,
                                                  CropResolver cropResolver) {
        this.dataSource = dataSource;
        this.cropResolver = cropResolver == null ? new CropResolver() : cropResolver;
    }

    public AgriculturalStructuredKnowledgeService() {
        this(null, new CropResolver());
    }

    @PostConstruct
    void initialize() {
        if (dataSource == null) return;
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
        } catch (SQLException error) {
            log.warn("Structured agricultural knowledge seed deferred: {} {}", error.getClass().getSimpleName(), error.getMessage());
        }
    }

    public LookupResult lookup(DiagnosisAnalyzeRequest request, KnowledgeQueryRewriter.RewrittenQuery rewritten) {
        if (dataSource == null || request == null) return LookupResult.unavailable();
        String cropCode = rewritten == null ? cropResolver.resolve(request.getCrop()).code() : rewritten.cropCode();
        String stageCode = rewritten == null ? request.getGrowthStage() : rewritten.stageCode();
        if (cropCode == null || cropCode.isBlank()) return LookupResult.empty("UNKNOWN_CROP");
        String query = rewritten == null ? request.getQuery() : rewritten.originalQuery();
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            List<StructuredHit> hits = new ArrayList<>();
            String category = category(query);
            if (category != null || attentionQuestion(query)) {
                hits.addAll(stageFacts(connection, cropCode, stageCode, category));
            }
            hits.addAll(signalFacts(connection, cropCode, stageCode, query));
            hits = dedupe(hits);
            boolean deterministic = !hits.isEmpty() && !explanatory(query)
                    && (category != null || signalQuestion(query));
            return new LookupResult("READY", hits, deterministic);
        } catch (SQLException error) {
            log.warn("Structured agricultural knowledge unavailable: {}", error.getClass().getSimpleName());
            return LookupResult.unavailable();
        }
    }

    public CoverageReport coverage() {
        if (dataSource == null) return new CoverageReport("UNAVAILABLE", 0, 0, Map.of());
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            Map<String, Integer> counts = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT CONCAT(crop_code, ':', stage_code, ':', knowledge_type), COUNT(*) " +
                            "FROM agri_stage_knowledge WHERE status='ACTIVE' GROUP BY crop_code,stage_code,knowledge_type")) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) counts.put(result.getString(1), result.getInt(2));
                }
            }
            int ruleCount = counts.values().stream().mapToInt(Integer::intValue).sum();
            int signalCount;
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM agri_signal_rule WHERE status='ACTIVE'")) {
                result.next(); signalCount = result.getInt(1);
            }
            return new CoverageReport("READY", ruleCount, signalCount, counts);
        } catch (SQLException error) {
            return new CoverageReport("UNAVAILABLE", 0, 0, Map.of());
        }
    }

    private List<StructuredHit> stageFacts(Connection connection, String cropCode, String stageCode, String category) throws SQLException {
        String sql = "SELECT rule_id,stage_code,knowledge_type,knowledge_key,content,priority,source_ref " +
                "FROM agri_stage_knowledge WHERE status='ACTIVE' AND crop_code=? " +
                (stageCode == null || stageCode.isBlank() ? "AND stage_code IS NULL " : "AND (stage_code=? OR stage_code IS NULL) ") +
                (category == null ? "" : "AND knowledge_type=? ") + "ORDER BY priority DESC,rule_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, cropCode);
            if (stageCode != null && !stageCode.isBlank()) statement.setString(index++, stageCode);
            if (category != null) statement.setString(index, category);
            try (ResultSet result = statement.executeQuery()) {
                List<StructuredHit> hits = new ArrayList<>();
                while (result.next()) hits.add(new StructuredHit(result.getString(1), cropCode, result.getString(2),
                        result.getString(3), result.getString(4), result.getString(5), result.getInt(6) / 100D,
                        result.getString(7), null, null, null));
                return hits;
            }
        }
    }

    private List<StructuredHit> signalFacts(Connection connection, String cropCode, String stageCode, String query) throws SQLException {
        if (query == null || query.isBlank()) return List.of();
        String sql = "SELECT rule_id,stage_code,signal_type,signal_key,judgement,recommendation,priority,source_ref,threshold_value,unit " +
                "FROM agri_signal_rule WHERE status='ACTIVE' AND (crop_code=? OR crop_code IS NULL) " +
                "AND (stage_code=? OR stage_code IS NULL) ORDER BY priority DESC,rule_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cropCode); statement.setString(2, stageCode);
            try (ResultSet result = statement.executeQuery()) {
                List<StructuredHit> hits = new ArrayList<>();
                String normalizedQuery = normalize(query);
                while (result.next()) {
                    String key = result.getString(4);
                    if (!signalMatches(normalizedQuery, key) && !(contains(query, "阈值", "标准") && result.getObject(9) != null)) continue;
                    String content = result.getString(5) + (result.getString(6) == null ? "" : " 建议：" + result.getString(6));
                    Object threshold = result.getObject(9);
                    Double thresholdValue = threshold instanceof Number number ? number.doubleValue() : null;
                    hits.add(new StructuredHit(result.getString(1), cropCode, result.getString(2), "SIGNAL",
                            key, content, result.getInt(7) / 100D, result.getString(8), thresholdValue, result.getString(10), result.getString(5)));
                }
                return hits;
            }
        }
    }

    private List<StructuredHit> dedupe(List<StructuredHit> input) {
        Map<String, StructuredHit> unique = new LinkedHashMap<>();
        input.forEach(hit -> unique.putIfAbsent(hit.ruleId(), hit));
        return new ArrayList<>(unique.values());
    }

    private String category(String value) {
        if (contains(value, "结束", "终止", "本茬", "采后恢复")) return "TABOO";
        if (contains(value, "病虫", "病害", "虫害", "病斑", "防治", "打药", "用药")) return "PEST_DISEASE";
        if (contains(value, "肥", "水分", "水肥", "灌溉", "浇水", "排水", "墒情")) return "FERTILIZER_WATER";
        if (contains(value, "修剪", "疏果", "采收", "收割", "清园", "操作", "农事", "怎么做", "保花", "保果", "记录")) return "FARM_OPERATION";
        if (contains(value, "禁忌", "不能", "不要", "避免", "禁止", "不宜")) return "TABOO";
        if (contains(value, "灾害", "高温", "暴雨", "降雨", "雨量", "霜冻", "干旱", "洪涝", "风害")) return "DISASTER";
        return null;
    }

    private boolean attentionQuestion(String value) { return contains(value, "要点", "注意", "建议", "管理", "怎么管", "怎么办"); }
    private boolean signalQuestion(String value) { return contains(value, "症状", "阈值", "判断", "黄化", "发黄", "病斑", "高温", "降雨", "雨量"); }
    private boolean signalMatches(String normalizedQuery, String key) {
        String normalizedKey = normalize(key);
        if (normalizedQuery.contains(normalizedKey)) return true;
        return normalizedQuery.replace("会", "").replace("可能", "").replace("出现", "").contains(normalizedKey);
    }
    private boolean explanatory(String value) { return contains(value, "为什么", "原因", "原理", "可能是什么", "如何判断"); }
    private boolean contains(String value, String... terms) {
        if (value == null) return false;
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
    private String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-—–/·（）()，,。:：;；]+", ""); }

    private void ensureSchema(Connection connection) throws SQLException {
        if (schemaChecked) return;
        synchronized (this) {
            if (schemaChecked) return;
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS agri_stage_knowledge (" +
                        "rule_id VARCHAR(112) NOT NULL, crop_code VARCHAR(64) NOT NULL, stage_code VARCHAR(64) NULL, " +
                        "knowledge_type VARCHAR(32) NOT NULL, knowledge_key VARCHAR(128) NOT NULL, content TEXT NOT NULL, " +
                        "is_deterministic BOOLEAN NOT NULL DEFAULT TRUE, source_ref VARCHAR(512) NULL, priority INT NOT NULL DEFAULT 50, " +
                        "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL, " +
                        "PRIMARY KEY(rule_id), UNIQUE KEY uk_agri_stage_rule(crop_code,stage_code,knowledge_type,knowledge_key), " +
                        "INDEX idx_agri_stage_lookup(crop_code,stage_code,knowledge_type,status))");
                statement.execute("CREATE TABLE IF NOT EXISTS agri_signal_rule (" +
                        "rule_id VARCHAR(112) NOT NULL, crop_code VARCHAR(64) NULL, stage_code VARCHAR(64) NULL, " +
                        "signal_type VARCHAR(32) NOT NULL, signal_key VARCHAR(128) NOT NULL, operator VARCHAR(8) NULL, " +
                        "threshold_value DECIMAL(12,3) NULL, unit VARCHAR(32) NULL, judgement TEXT NOT NULL, recommendation TEXT NULL, " +
                        "source_ref VARCHAR(512) NULL, priority INT NOT NULL DEFAULT 50, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', " +
                        "created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL, PRIMARY KEY(rule_id), " +
                        "UNIQUE KEY uk_agri_signal_rule(crop_code,stage_code,signal_type,signal_key), INDEX idx_agri_signal_lookup(crop_code,stage_code,status))");
            }
            seedStageRules(connection);
            seedSignalRules(connection);
            schemaChecked = true;
        }
    }

    private void seedStageRules(Connection connection) throws SQLException {
        String sql = "INSERT IGNORE INTO agri_stage_knowledge(rule_id,crop_code,stage_code,knowledge_type,knowledge_key,content,is_deterministic,source_ref,priority,status,created_at,updated_at) VALUES(?,?,?,?,?,?,TRUE,?,?, 'ACTIVE',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (StageSeed seed : STAGE_SEEDS) {
                statement.setString(1, seed.ruleId); statement.setString(2, seed.cropCode); statement.setString(3, seed.stageCode);
                statement.setString(4, seed.type); statement.setString(5, seed.key); statement.setString(6, seed.content);
                statement.setString(7, SOURCE_REF); statement.setInt(8, seed.priority); statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void seedSignalRules(Connection connection) throws SQLException {
        String sql = "INSERT IGNORE INTO agri_signal_rule(rule_id,crop_code,stage_code,signal_type,signal_key,operator,threshold_value,unit,judgement,recommendation,source_ref,priority,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (SignalSeed seed : SIGNAL_SEEDS) {
                statement.setString(1, seed.ruleId); statement.setString(2, seed.cropCode); statement.setString(3, seed.stageCode);
                statement.setString(4, seed.type); statement.setString(5, seed.key); statement.setString(6, seed.operator);
                if (seed.threshold == null) statement.setNull(7, java.sql.Types.DECIMAL); else statement.setDouble(7, seed.threshold);
                statement.setString(8, seed.unit); statement.setString(9, seed.judgement); statement.setString(10, seed.recommendation);
                statement.setString(11, SOURCE_REF); statement.setInt(12, seed.priority); statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static StageSeed stage(String crop, String stage, String type, String key, String content, int priority) {
        return new StageSeed("agri-" + crop + "-" + stage + "-" + type.toLowerCase(Locale.ROOT), crop, stage, type, key, content, priority);
    }

    private static final List<StageSeed> STAGE_SEEDS = List.of(
            stage("plum", "post_harvest", "PEST_DISEASE", "病虫监测", "采后恢复期巡查叶片病斑、枝干病虫和落果，发现异常先记录部位与扩展趋势，再按确诊结果处理。", 95),
            stage("plum", "post_harvest", "FERTILIZER_WATER", "树势恢复", "采后补水和秋肥应结合土壤墒情、树势与检测结果，保持根区通气，避免长期积水和过量氮肥。", 90),
            stage("plum", "post_harvest", "FARM_OPERATION", "采后清园", "采后及时清理病果、落叶和修剪残体，配合必要的整形修剪，促进秋梢和树势恢复。", 90),
            stage("plum", "post_harvest", "TABOO", "恢复期禁忌", "采后恢复期不宜大水漫灌、重施速效氮肥或在未确认病因前反复混用药剂。", 92),
            stage("plum", "post_harvest", "DISASTER", "连续降雨", "连续降雨后重点检查根区积水、枝叶病斑和秋梢长势，优先排水通风并记录受害范围。", 88),
            stage("plum", "dormancy", "PEST_DISEASE", "越冬病虫", "休眠期检查枝干、树皮缝和园地残体中的越冬病虫源，清园结果应留痕。", 82),
            stage("plum", "dormancy", "FERTILIZER_WATER", "休眠期水分", "休眠期按土壤墒情管理水分，避免根区长期过湿；肥水安排以当地农艺方案为准。", 80),
            stage("plum", "dormancy", "FARM_OPERATION", "冬季修剪", "休眠期可按树形和枝势实施冬季修剪，剪口与枝条去留应由登记树形和现场情况决定。", 84),
            stage("plum", "dormancy", "TABOO", "休眠期禁忌", "休眠期不要把修剪、清园等农事操作写成物候期，也不要仅凭日期替代现场登记。", 86),
            stage("plum", "fruit_development", "PEST_DISEASE", "果实膨大病虫", "果实发育期重点观察果面、叶片和枝梢病虫变化，防治前应先确认症状与发生部位。", 86),
            stage("plum", "fruit_development", "FERTILIZER_WATER", "膨果肥水", "果实发育期保持根区水分相对稳定，肥水根据树势、负载和土壤检测调整，避免忽干忽湿。", 86),
            stage("plum", "fruit_development", "FARM_OPERATION", "幼果管理", "幼果和硬核阶段的疏果、枝梢管理等操作按当地农艺规程执行，并记录执行时间。", 84),
            stage("plum", "fruit_development", "TABOO", "膨果期禁忌", "果实发育期不宜在高温、强降雨前后盲目施药或一次性大水大肥。", 86),
            stage("plum", "fruit_development", "DISASTER", "高温暴雨", "高温或暴雨后检查裂果、落果、积水和枝梢受损情况，再决定补水、排水或修复措施。", 84),
            stage("plum", "ripening", "PEST_DISEASE", "成熟期病害", "成熟期巡查果面腐烂、裂果和病斑，采收和分拣时隔离异常果，避免病果继续传播。", 84),
            stage("plum", "ripening", "FERTILIZER_WATER", "成熟期水分", "成熟期水分管理保持稳定，临近采收避免剧烈干湿变化造成裂果风险。", 82),
            stage("plum", "ripening", "FARM_OPERATION", "成熟采收", "成熟期按品种、成熟度和运输安排分批采收，采收工具与容器保持清洁。", 88),
            stage("plum", "ripening", "TABOO", "成熟期禁忌", "成熟期不应只按固定日期判断采收，也不应把采收、修剪等操作当作下一物候期。", 86),

            stage("citrus", "bud_differentiation", "PEST_DISEASE", "越冬病虫", "柑橘花芽形态分化和越冬期巡查枝梢、叶片及果实挂树情况，区分病虫与自然衰老。", 84),
            stage("citrus", "bud_differentiation", "FERTILIZER_WATER", "越冬肥水", "花芽分化期保持根区不过湿，肥水安排兼顾树势和挂果量，避免刺激性徒长。", 82),
            stage("citrus", "bud_differentiation", "FARM_OPERATION", "越冬巡园", "越冬期记录花芽、秋梢和挂树果实状态，为春梢和现蕾期管理提供连续依据。", 82),
            stage("citrus", "bud_differentiation", "TABOO", "常绿越冬禁忌", "柑橘不设落叶果树式休眠期，不能把冬季花芽分化期答成脆李休眠或采后恢复期。", 98),
            stage("citrus", "flowering", "PEST_DISEASE", "花期病虫", "花期重点观察花器、嫩梢和幼果异常，防治应避开对授粉和访花昆虫不利的时段。", 90),
            stage("citrus", "flowering", "FERTILIZER_WATER", "花期肥水", "花期水分保持平稳，追肥和灌水按树势、花量及当地方案执行，避免过量刺激新梢。", 86),
            stage("citrus", "flowering", "FARM_OPERATION", "保花管理", "花期记录初花、盛花、末花和落花情况，保花保果等属于农事操作，不是物候期名称。", 90),
            stage("citrus", "flowering", "TABOO", "花期禁忌", "花期不宜在高温或盛花时段进行可能伤花的操作，具体用药必须遵循登记标签和当地规程。", 90),
            stage("citrus", "fruit_development", "PEST_DISEASE", "膨果期病虫", "果实膨大期检查果实、叶片和夏秋梢，重点记录病斑、虫口和落果变化后再制定防治方案。", 92),
            stage("citrus", "fruit_development", "FERTILIZER_WATER", "膨果期肥水", "果实膨大期保持根区水分稳定，肥水结合树势、负载和土壤检测，避免忽干忽湿。", 90),
            stage("citrus", "fruit_development", "FARM_OPERATION", "夏秋梢管理", "果实膨大期可同步记录夏梢、秋梢和花芽生理分化等并行事件，不能强行压成一个操作名称。", 92),
            stage("citrus", "fruit_development", "TABOO", "膨果期禁忌", "膨果期不宜在连续降雨或高温前后盲目施药、重灌或重施氮肥。", 90),
            stage("citrus", "fruit_development", "DISASTER", "高温暴雨", "高温、暴雨后检查果实日灼、裂果、积水、落果和新梢受损情况，先处置水分与通风问题。", 90),
            stage("citrus", "harvest", "PEST_DISEASE", "采收期病果", "采收期将病果、伤果与商品果分开处理，记录异常果比例和来源地块。", 82),
            stage("citrus", "harvest", "FERTILIZER_WATER", "采收期水分", "采收期保持树体水分平稳，晚熟品种挂树越冬时按品种和树势管理。", 82),
            stage("citrus", "harvest", "FARM_OPERATION", "分批采收", "柑橘按品种成熟档和果实成熟度分批采收，采收后同步更新地块和品种记录。", 88),
            stage("citrus", "harvest", "TABOO", "柑橘无终止期", "柑橘采收不是生育周期终止；常绿树仍有跨年物候，不能套用水稻收获后本茬结束。", 98),

            stage("rice", "tillering", "PEST_DISEASE", "分蘖期病虫", "分蘖期巡查叶片、茎基部和田间水层，记录病斑、虫口和缺苗范围后再决定防治。", 86),
            stage("rice", "tillering", "FERTILIZER_WATER", "分蘖期水肥", "分蘖期水肥管理兼顾群体长势和田间墒情，避免长期深水或偏施氮肥造成群体过旺。", 88),
            stage("rice", "tillering", "FARM_OPERATION", "分蘖管理", "分蘖期记录有效分蘖、田间水层和杂草情况，晒田、控水等操作按当地栽培方案执行。", 86),
            stage("rice", "tillering", "TABOO", "分蘖期禁忌", "分蘖期不要把拔节、抽穗等后续生育阶段提前使用，也不要在缺少田间证据时盲目加肥加水。", 86),
            stage("rice", "filling", "PEST_DISEASE", "灌浆期病虫", "灌浆结实期观察穗部、叶片和倒伏情况，病虫防治应结合实际症状和登记用药要求。", 86),
            stage("rice", "filling", "FERTILIZER_WATER", "灌浆水分", "灌浆期保持适宜水分，避免长期积水或过早断水造成灌浆受影响。", 88),
            stage("rice", "filling", "FARM_OPERATION", "成熟观察", "灌浆期记录乳熟、蜡熟和籽粒充实度，为适期收获提供依据。", 86),
            stage("rice", "filling", "TABOO", "灌浆期禁忌", "灌浆期不宜仅按日历日期提前收割，也不应把水稻收获后写成木本果树采后恢复期。", 92),
            stage("rice", "harvest", "PEST_DISEASE", "收获期损失", "收获期检查倒伏、穗部病害和田间落粒，分区记录收获损失。", 80),
            stage("rice", "harvest", "FERTILIZER_WATER", "收获后田块", "收获后本茬生育周期结束，后续水肥安排应转入冬闲或后作方案。", 90),
            stage("rice", "harvest", "FARM_OPERATION", "适期收割", "成熟收获期按籽粒成熟度、天气和机械条件安排收割，收后更新本茬状态。", 90),
            stage("rice", "harvest", "TABOO", "本茬终止", "一季中稻收获后不要再派发采后恢复或休眠等木本阶段；晚稻、再生稻应使用独立物候历。", 98),

            stage("rice_late", "heading_flowering", "PEST_DISEASE", "晚稻抽穗病虫", "晚稻抽穗扬花期观察穗部、叶片和田间湿度，防治按实际症状和登记要求执行。", 84),
            stage("rice_late", "heading_flowering", "FERTILIZER_WATER", "晚稻抽穗水分", "晚稻抽穗扬花期保持田间水分适宜，避免持续深水和过早断水。", 84),
            stage("rice_late", "heading_flowering", "FARM_OPERATION", "晚稻齐穗记录", "记录晚稻抽穗、齐穗和扬花时间，后续以灌浆进程而不是中稻日历判断收获。", 86),
            stage("rice_late", "heading_flowering", "TABOO", "晚稻不套中稻", "晚稻和再生稻不能套用一季中稻的九月收获后结论，应使用独立历和地块登记。", 98),
            stage("rice_late", "filling", "PEST_DISEASE", "晚稻灌浆病虫", "晚稻灌浆期观察穗部充实、叶片病斑和倒伏风险，按田间证据安排处置。", 86),
            stage("rice_late", "filling", "FERTILIZER_WATER", "晚稻灌浆水分", "晚稻灌浆期保持水分适宜，避免过早断水；具体安排以当地栽培方案和天气为准。", 86),
            stage("rice_late", "filling", "FARM_OPERATION", "晚稻成熟观察", "晚稻灌浆期记录乳熟、蜡熟和籽粒充实度，收获期按本历另行计算。", 84),
            stage("rice_late", "filling", "TABOO", "晚稻收获判断", "晚稻九月仍可能处于灌浆或抽穗阶段，不得直接套用中稻已收获结论。", 98)
    );

    private static final List<SignalSeed> SIGNAL_SEEDS = List.of(
            new SignalSeed("signal-plum-yellowing", "plum", "post_harvest", "SYMPTOM", "叶片黄化", null, null, null, "叶片黄化需要结合叶龄、叶脉颜色、根区水分和病斑位置判断，不能仅凭颜色定病。", "先拍照并检查根区积水、施肥记录和病斑扩展，再决定是否处理。", 94),
            new SignalSeed("signal-plum-lesion", "plum", null, "SYMPTOM", "病斑", null, null, null, "病斑判断至少需要记录形状、边缘、叶龄、枝位和扩展速度。", "补充清晰近照和地块位置，避免未确诊前混用药剂。", 90),
            new SignalSeed("signal-citrus-rain", "citrus", "fruit_development", "THRESHOLD", "连续降雨", ">", 3D, "天", "连续降雨超过3天时，柑橘根区过湿和病害风险上升。", "优先检查排水、根区通气和叶果病斑，再按实际症状处置。", 96),
            new SignalSeed("signal-citrus-heat", "citrus", "fruit_development", "THRESHOLD", "高温", ">=", 35D, "℃", "日最高温度达到35℃及以上时，果实和嫩梢热害风险上升。", "加强遮阴、通风和水分监测，避免高温时段进行刺激性操作。", 96),
            new SignalSeed("signal-citrus-yellowing", "citrus", null, "SYMPTOM", "叶片黄化", null, null, null, "柑橘叶片黄化可能与根区水分、营养、根系或病害有关，需要结合叶脉和土壤信息判断。", "先核对水肥记录并检查根区，再决定补肥或防治。", 94),
            new SignalSeed("signal-rice-rain", "rice", "filling", "THRESHOLD", "连续降雨", ">", 3D, "天", "灌浆期连续降雨超过3天时，倒伏、病害和籽粒充实风险上升。", "检查田间积水、倒伏和穗部病害，及时记录受灾范围。", 94),
            new SignalSeed("signal-rice-heat", "rice", "filling", "THRESHOLD", "高温", ">=", 35D, "℃", "灌浆期日最高温度达到35℃及以上时，高温胁迫风险上升。", "结合水分和抽穗灌浆进程监测，不用单一温度值替代田间判断。", 92),
            new SignalSeed("signal-rice-late-rain", "rice_late", "filling", "THRESHOLD", "连续降雨", ">", 3D, "天", "晚稻灌浆期连续降雨超过3天时，应关注倒伏、病害和收获窗口。", "结合晚稻独立物候历检查穗部和田间排水情况。", 94)
    );

    public record StructuredHit(String ruleId, String cropCode, String stageCode, String knowledgeType,
                                String key, String content, double score, String sourceRef,
                                Double thresholdValue, String unit, String judgement) { }
    public record LookupResult(String status, List<StructuredHit> hits, boolean deterministic) {
        public LookupResult { hits = hits == null ? List.of() : List.copyOf(hits); }
        public static LookupResult unavailable() { return new LookupResult("UNAVAILABLE", List.of(), false); }
        public static LookupResult empty(String status) { return new LookupResult(status, List.of(), false); }
    }
    public record CoverageReport(String status, int stageRuleCount, int signalRuleCount, Map<String, Integer> counts) { }
    private record StageSeed(String ruleId, String cropCode, String stageCode, String type, String key, String content, int priority) { }
    private record SignalSeed(String ruleId, String cropCode, String stageCode, String type, String key, String operator, Double threshold, String unit, String judgement, String recommendation, int priority) { }
}
