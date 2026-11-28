package com.mei.zhgy.service.copilot;

import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.agriculture.FieldFactsService;
import com.mei.zhgy.service.agriculture.PhenologyResult;
import com.mei.zhgy.service.agriculture.PhenologyService;

import java.time.LocalDate;

/** Keeps deterministic phenology answers and retrieval-stage selection on the same calendar. */
final class CopilotPhenologyGuidance {
    private final CropResolver cropResolver;
    private final PhenologyService phenologyService;

    CopilotPhenologyGuidance(CropResolver cropResolver, PhenologyService phenologyService) {
        this.cropResolver = cropResolver;
        this.phenologyService = phenologyService;
    }

    boolean asksTemporalPhenology(String message) {
        String normalized = safe(message);
        return contains(normalized, "下一个", "下一阶段", "下一期", "之后", "再下一个", "上一个", "上一阶段", "上一期", "之前")
                || contains(normalized, "现在", "当前", "目前") && contains(normalized, "物候期", "生育期", "生长阶段",
                "什么时期", "什么阶段", "哪一期", "哪一个阶段");
    }

    String temporalPhenologyAnswer(String message, CopilotFacts facts) {
        String normalized = safe(message);
        boolean asksNext = normalized.contains("下一个") || normalized.contains("下一阶段") || normalized.contains("下一期") || normalized.contains("之后") || normalized.contains("再下一个");
        boolean asksPrevious = normalized.contains("上一个") || normalized.contains("上一阶段") || normalized.contains("上一期") || normalized.contains("之前");
        boolean asksTemporal = asksNext || asksPrevious;
        if (!asksTemporalPhenology(normalized)) return null;
        if (facts == null || facts.growthStageLabel() == null || facts.cropLabel() == null) {
            String status = facts == null ? "UNAVAILABLE" : facts.status();
            if ("UNKNOWN_CROP".equals(status)) return "当前地块登记的作物暂不在物候目录中。请核对地块档案中的作物名称，再查询物候。";
            return "当前未绑定可用地块。今天各支持作物按通用历推算为：" + publicPhenologySummary()
                    + "。如需唯一结论，请在问题中明确作物。";
        }
        String crop = facts.cropLabel() == null ? "当前作物" : facts.cropLabel();
        String basis = facts.sources() == null ? null : facts.sources().get("phenologyBasis");
        String basisNote = safe(basis).isBlank() ? "" : "\n\n推算依据 " + basis + "。";
        if (asksTemporal) {
            boolean next = asksNext && !asksPrevious;
            int distance = normalized.contains("再下一个") ? 2 : 1;
            String target = next ? stageAt(facts, distance) : stageAt(facts, -1);
            if (target == null) {
                if (next && facts.cycleEnded()) return temporalPrefix(facts) + crop + "当前为" + facts.growthStageLabel() + "；本茬已结束，没有下一物候期。";
                return temporalPrefix(facts) + crop + "当前为" + facts.growthStageLabel() + "，暂未配置可确定的" + (next ? "下一" : "上一") + "物候期。";
            }
            String relation = next ? (distance == 2 ? "再下一物候期" : "下一物候期") : "上一物候期";
            String days = next && distance == 1 && facts.daysUntilNext() != null ? "，按历法约" + facts.daysUntilNext() + "天后进入" : "";
            return temporalPrefix(facts) + crop + "当前处于" + facts.growthStageLabel() + "；" + relation + "是" + target + days + "。" + basisNote;
        }
        boolean calibrated = facts.sources() != null && "VERIFIED_PHENOLOGY_CORRECTION".equals(facts.sources().get("currentStage"));
        String source = calibrated ? "根据已核验的观测或积温校准，"
                : facts.stageMismatch()
                ? "地块登记为" + facts.registeredGrowthStageLabel() + "，但按服务器日期推算为" + facts.dateDerivedGrowthStageLabel() + "；当前先以登记值为准，请确认。"
                : facts.registeredGrowthStage() == null ? "根据服务器日期推算，" : "根据当前地块的服务端登记事实，";
        if ("PUBLIC_GENERIC".equals(facts.status())) source = "按服务器日期和" + crop + "通用物候历推算，";
        return ("PUBLIC_GENERIC".equals(facts.status()) ? "未使用地块实时数据，" : "截至" + facts.currentDate() + "，") + source + crop + "目前处于" + facts.growthStageLabel() + ("PUBLIC_GENERIC".equals(facts.status()) ? "。该结论不是你的地块实时事实。" : "。") + basisNote;
    }

    boolean isCompositePhenologyGuidance(String message) {
        String normalized = safe(message);
        boolean asksPhenology = normalized.contains("物候期") || normalized.contains("生育期") || normalized.contains("生长阶段")
                || normalized.contains("什么时期") || normalized.contains("什么阶段") || normalized.contains("哪一期") || normalized.contains("哪一个阶段");
        boolean asksTemporal = normalized.contains("下一个") || normalized.contains("下一阶段") || normalized.contains("下一期")
                || normalized.contains("之后") || normalized.contains("再下一个") || normalized.contains("上一个")
                || normalized.contains("上一阶段") || normalized.contains("上一期") || normalized.contains("之前")
                || normalized.contains("现在") || normalized.contains("当前") || normalized.contains("目前");
        return asksPhenology && asksTemporal && asksGuidance(normalized);
    }

    private boolean asksGuidance(String message) {
        return contains(safe(message), "农事指导", "农事建议", "管理指导", "管理建议", "管理重点", "注意事项",
                "指导什么", "怎么管理", "如何管理", "怎么管", "怎么办", "怎么做", "如何做", "做什么",
                "干什么", "干啥", "要做什么", "要干什么", "接下来", "后续", "下一步", "需要做", "应该做",
                "如何安排", "怎么安排", "注意什么", "注意啥", "管护", "田间管理", "栽培管理", "农活",
                "建议", "指导", "措施", "水肥管理", "肥水管理", "病虫防治");
    }

    String knowledgeStageFor(String message, CopilotFacts facts) {
        if (facts == null) return null;
        String normalized = safe(message);
        if (asksGuidance(normalized)) {
            if (contains(normalized, "下一个", "下一阶段", "下一期", "之后", "再下一个")
                    && facts.nextGrowthStage() != null && !facts.nextGrowthStage().isBlank()) {
                return facts.nextGrowthStage();
            }
            if (contains(normalized, "上一个", "上一阶段", "上一期", "之前")
                    && facts.previousGrowthStage() != null && !facts.previousGrowthStage().isBlank()) {
                return facts.previousGrowthStage();
            }
        }
        return facts.growthStage();
    }

    private String temporalPrefix(CopilotFacts facts) {
        return "PUBLIC_GENERIC".equals(facts.status())
                ? "未使用地块实时数据，按" + facts.cropLabel() + "通用物候历和服务器日期（" + facts.currentDate() + "）推算，"
                : "截至" + facts.currentDate() + "，";
    }

    private String publicPhenologySummary() {
        LocalDate date = LocalDate.now(FieldFactsService.ZONE);
        StringBuilder summary = new StringBuilder();
        for (var crop : cropResolver.supportedCrops()) {
            PhenologyResult result = phenologyService.calculate(crop.code(), date, null);
            if (summary.length() > 0) summary.append("；");
            summary.append(crop.name()).append("：").append(result.currentName() == null ? "暂无已配置阶段" : result.currentName());
        }
        return summary.toString();
    }

    private String stageAt(CopilotFacts facts, int offset) {
        if (facts.orderedStageNames() == null || facts.orderedStageNames().isEmpty() || facts.growthStageLabel() == null) return null;
        int current = facts.orderedStageNames().indexOf(facts.growthStageLabel());
        if (current < 0) return null;
        int target = current + offset;
        if ("deciduous_annual_cycle".equalsIgnoreCase(facts.cycleType()) || "evergreen_annual_cycle".equalsIgnoreCase(facts.cycleType())) {
            int size = facts.orderedStageNames().size();
            target = ((target % size) + size) % size;
        }
        return target < 0 || target >= facts.orderedStageNames().size() ? null : facts.orderedStageNames().get(target);
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private static boolean contains(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
}
