package com.mei.zhgy.service.copilot;

import com.mei.zhgy.mapper.FarmMapper;
import com.mei.zhgy.mapper.UserMapper;
import com.mei.zhgy.service.agent.AgentAccessException;
import com.mei.zhgy.service.agent.AgentRun;
import com.mei.zhgy.service.agent.AgentRuntime;
import com.mei.zhgy.service.diagnosis.DiagnosisPersistenceService;
import com.mei.zhgy.service.diagnosis.DiagnosisRecord;
import com.mei.zhgy.service.field.FarmField;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.service.operations.OperationsTaskService;
import com.mei.zhgy.service.operations.OperationsWriteException;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves stable IDs against server-owned services; page data remains a hint only. */
@Service
public class CopilotContextResolver {
    private static final Pattern FIELD_ID = Pattern.compile("\\bfield-[A-Za-z0-9_-]+\\b");
    private static final Pattern DIAGNOSIS_ID = Pattern.compile("\\bdiagnosis-[A-Za-z0-9_-]+\\b");
    private static final Pattern RECOMMENDATION_ID = Pattern.compile("\\brecommendation-[A-Za-z0-9_-]+\\b");
    private static final Pattern RUN_ID = Pattern.compile("\\brun-[A-Za-z0-9_-]+\\b");
    private static final Pattern TASK_ID = Pattern.compile("\\btask-[A-Za-z0-9_-]+\\b");
    private static final Pattern OUTCOME_ID = Pattern.compile("\\boutcome-[A-Za-z0-9_-]+\\b");
    private static final Pattern ACTION_ID = Pattern.compile("\\baction-[A-Za-z0-9_-]+\\b");

    private final FarmFieldService fields;
    private final DiagnosisPersistenceService diagnoses;
    private final OperationsTaskService operations;
    private final AgentRuntime agents;
    private final FarmMapper farms;
    private final UserMapper users;

    @Autowired
    public CopilotContextResolver(FarmFieldService fields,
                                  DiagnosisPersistenceService diagnoses,
                                  OperationsTaskService operations,
                                  AgentRuntime agents,
                                  FarmMapper farms,
                                  UserMapper users) {
        this.fields = fields;
        this.diagnoses = diagnoses;
        this.operations = operations;
        this.agents = agents;
        this.farms = farms;
        this.users = users;
    }

    public Resolution resolve(CopilotRouteRequest request,
                              CopilotPageContext page,
                              CopilotStableContext session,
                              CopilotIntent intent,
                              Long userId) {
        CopilotStableContext hints = merge(request, page, session);
        String message = request == null ? "" : request.getMessage();
        if (request != null) {
            hints.setFieldId(firstNonBlank(hints.getFieldId(), find(FIELD_ID, message)));
            hints.setDiagnosisId(firstNonBlank(hints.getDiagnosisId(), find(DIAGNOSIS_ID, message)));
            hints.setRecommendationId(firstNonBlank(hints.getRecommendationId(), find(RECOMMENDATION_ID, message)));
            hints.setRunId(firstNonBlank(hints.getRunId(), find(RUN_ID, message)));
            hints.setTaskId(firstNonBlank(hints.getTaskId(), find(TASK_ID, message)));
            hints.setOutcomeId(firstNonBlank(hints.getOutcomeId(), find(OUTCOME_ID, message)));
            hints.setActionId(firstNonBlank(hints.getActionId(), find(ACTION_ID, message)));
        }

        try {
            if (requiresFieldValidation(intent, hints, message)) {
                if (blank(hints.getFieldId())) return clarify("请先打开或明确要处理的地块。");
                FarmField field = fields.get(hints.getFieldId(), userId);
                String fieldFarmId = field.getFarmId() == null ? null : String.valueOf(field.getFarmId());
                if (!blank(hints.getFarmId()) && !hints.getFarmId().equals(fieldFarmId)) return stale("当前地块与农场上下文不一致，请重新选择地块。");
                hints.setFarmId(fieldFarmId);
            } else if (!blank(hints.getFarmId())) {
                validateFarm(hints.getFarmId(), userId);
            }

            DiagnosisRecord diagnosisRecord = null;
            if (requiresDiagnosis(intent)) {
                if (blank(hints.getDiagnosisId())) return clarify("请先打开一条诊断，或先询问这个地块有什么问题。");
                diagnosisRecord = diagnoses.findRecord(hints.getDiagnosisId(), userId).orElse(null);
                if (diagnosisRecord == null) return forbidden();
                if (!blank(hints.getFieldId()) && !blank(diagnosisRecord.getFieldId()) && !hints.getFieldId().equals(diagnosisRecord.getFieldId())) {
                    return stale("当前诊断不属于当前地块，请重新选择诊断。");
                }
                if (blank(hints.getFieldId())) hints.setFieldId(diagnosisRecord.getFieldId());
                if (blank(hints.getFarmId()) && diagnosisRecord.getFarmId() != null) hints.setFarmId(String.valueOf(diagnosisRecord.getFarmId()));
            } else if ((intent == CopilotIntent.DIAGNOSIS_REQUEST || intent == CopilotIntent.DIAGNOSIS_QUERY) && !blank(hints.getDiagnosisId())) {
                diagnosisRecord = diagnoses.findRecord(hints.getDiagnosisId(), userId).orElse(null);
                if (diagnosisRecord == null) return forbidden();
                if (!blank(hints.getFieldId()) && !blank(diagnosisRecord.getFieldId()) && !hints.getFieldId().equals(diagnosisRecord.getFieldId())) {
                    return stale("当前诊断不属于当前地块，请重新选择诊断。");
                }
                if (blank(hints.getFieldId())) hints.setFieldId(diagnosisRecord.getFieldId());
                if (blank(hints.getFarmId()) && diagnosisRecord.getFarmId() != null) hints.setFarmId(String.valueOf(diagnosisRecord.getFarmId()));
            } else if (intent == CopilotIntent.NAVIGATION_REQUEST && !blank(hints.getDiagnosisId())) {
                diagnosisRecord = diagnoses.findRecord(hints.getDiagnosisId(), userId).orElse(null);
                if (diagnosisRecord == null) return forbidden();
            }

            if (intent == CopilotIntent.ACTION_REQUEST) {
                if (blank(hints.getRecommendationId()) && diagnosisRecord != null && firstRecommendation(message)) {
                    hints.setRecommendationId(firstRecommendationId(diagnosisRecord));
                }
                if (blank(hints.getRecommendationId())) return clarify("请明确要执行哪条建议；可以说“安排第一条建议”。", hints);
                Optional<DiagnosisAnalysisResponse.Recommendation> recommendation = diagnoses.findRecommendation(hints.getDiagnosisId(), hints.getRecommendationId(), userId);
                if (recommendation.isEmpty()) return forbidden();
            }

            if (intent == CopilotIntent.OPERATIONS_STATUS_QUERY || intent == CopilotIntent.OUTCOME_QUERY) {
                if (blank(hints.getTaskId())) return clarify("请提供任务 ID，或先打开要查看的任务。");
                var task = operations.getTask(hints.getTaskId(), userId);
                Object outcomeId = task.get("outcomeId");
                if (outcomeId != null && blank(hints.getOutcomeId())) hints.setOutcomeId(String.valueOf(outcomeId));
                if (intent == CopilotIntent.OUTCOME_QUERY) {
                    var outcome = operations.getOutcome(hints.getTaskId(), userId);
                    Object resolvedOutcomeId = outcome.get("outcomeId");
                    if (resolvedOutcomeId == null) return notFound();
                    if (blank(hints.getOutcomeId())) hints.setOutcomeId(String.valueOf(resolvedOutcomeId));
                    else if (!hints.getOutcomeId().equals(String.valueOf(resolvedOutcomeId))) return stale("Outcome 与当前任务不匹配。");
                }
            }

            if (intent == CopilotIntent.NAVIGATION_REQUEST && "/analysis".equals(route(message)) && !blank(hints.getRunId())) {
                agents.require(hints.getRunId(), userId);
            }

            if (intent == CopilotIntent.NAVIGATION_REQUEST && "/operations".equals(route(message)) && !blank(hints.getTaskId())) {
                operations.getTask(hints.getTaskId(), userId);
            }

            if (!blank(hints.getRunId()) && (intent == CopilotIntent.ACTION_REQUEST || intent == CopilotIntent.NAVIGATION_REQUEST)) {
                AgentRun run = agents.require(hints.getRunId(), userId);
                if (!blank(hints.getActionId()) && run.getActions() != null && run.getActions().stream().noneMatch(action -> hints.getActionId().equals(action.getId()))) {
                    return forbidden();
                }
            }
            sanitizeForIntent(hints, intent);
            return new Resolution(CopilotResolutionStatus.RESOLVED, hints, null);
        } catch (FieldAccessException error) {
            return error.getHttpStatus() == 403 ? forbidden() : error.getHttpStatus() == 404 ? notFound() : forbidden();
        } catch (AgentAccessException error) {
            return error.getHttpStatus() == 403 ? forbidden() : notFound();
        } catch (OperationsWriteException error) {
            return "FORBIDDEN".equalsIgnoreCase(error.getCode()) ? forbidden() : notFound();
        } catch (IllegalArgumentException error) {
            return notFound();
        }
    }

    private void validateFarm(String farmId, Long userId) {
        if (farms == null || users == null || userId == null) throw new FieldAccessException(403, "FARM_ACCESS_DENIED", "farm access denied");
        Integer numeric;
        try { numeric = Integer.valueOf(farmId); } catch (NumberFormatException error) { throw new FieldAccessException(404, "FARM_NOT_FOUND", "farm not found"); }
        var farm = farms.getFarmById(numeric);
        var user = users.getById(userId.intValue());
        boolean admin = user != null && "admin".equalsIgnoreCase(user.getRole());
        if (farm == null) throw new FieldAccessException(404, "FARM_NOT_FOUND", "farm not found");
        if (!admin && (farm.getUserId() == null || !userId.equals(farm.getUserId().longValue()))) throw new FieldAccessException(403, "FARM_ACCESS_DENIED", "farm access denied");
    }

    private CopilotStableContext merge(CopilotRouteRequest request, CopilotPageContext page, CopilotStableContext session) {
        String selectedField = firstNonBlank(request == null ? null : request.getFieldId(), page == null ? null : page.getFieldId(), session == null ? null : session.getFieldId());
        boolean fieldChanged = session != null && !blank(session.getFieldId()) && !blank(selectedField) && !session.getFieldId().equals(selectedField);
        return CopilotStableContext.builder()
                .farmId(firstNonBlank(request == null ? null : request.getFarmId(), page == null ? null : page.getFarmId(), session == null ? null : session.getFarmId()))
                .fieldId(selectedField)
                .diagnosisId(firstNonBlank(request == null ? null : request.getDiagnosisId(), page == null ? null : page.getDiagnosisId(), fieldChanged ? null : session == null ? null : session.getDiagnosisId()))
                .recommendationId(firstNonBlank(request == null ? null : request.getRecommendationId(), page == null ? null : page.getRecommendationId(), fieldChanged ? null : session == null ? null : session.getRecommendationId()))
                .runId(firstNonBlank(request == null ? null : request.getRunId(), page == null ? null : page.getRunId(), fieldChanged ? null : session == null ? null : session.getRunId()))
                .actionId(firstNonBlank(request == null ? null : request.getActionId(), page == null ? null : page.getActionId(), fieldChanged ? null : session == null ? null : session.getActionId()))
                .taskId(firstNonBlank(request == null ? null : request.getTaskId(), page == null ? null : page.getTaskId(), fieldChanged ? null : session == null ? null : session.getTaskId()))
                .outcomeId(firstNonBlank(request == null ? null : request.getOutcomeId(), page == null ? null : page.getOutcomeId(), fieldChanged ? null : session == null ? null : session.getOutcomeId()))
                .build();
    }

    private boolean requiresField(CopilotIntent intent) { return intent == CopilotIntent.FIELD_STATUS_QUERY || intent == CopilotIntent.DIAGNOSIS_REQUEST; }
    private boolean requiresDiagnosis(CopilotIntent intent) { return intent == CopilotIntent.RECOMMENDATION_QUERY || intent == CopilotIntent.ACTION_REQUEST; }
    private boolean requiresFieldValidation(CopilotIntent intent, CopilotStableContext hints, String message) {
        return requiresField(intent) || (intent == CopilotIntent.KNOWLEDGE_QUERY && !blank(hints.getFieldId()))
                || (requiresDiagnosis(intent) && !blank(hints.getFieldId()))
                || (intent == CopilotIntent.NAVIGATION_REQUEST && "/farm/map".equals(route(message)) && !blank(hints.getFieldId()));
    }
    private void sanitizeForIntent(CopilotStableContext context, CopilotIntent intent) {
        if (intent == CopilotIntent.FIELD_STATUS_QUERY) {
            context.setDiagnosisId(null); context.setRecommendationId(null); context.setRunId(null); context.setActionId(null); context.setTaskId(null); context.setOutcomeId(null);
        } else if (intent == CopilotIntent.PRIORITIES_QUERY || intent == CopilotIntent.HISTORICAL_CASE_QUERY) {
            context.setDiagnosisId(null); context.setRecommendationId(null); context.setRunId(null); context.setActionId(null); context.setTaskId(null); context.setOutcomeId(null);
        } else if (intent == CopilotIntent.DIAGNOSIS_REQUEST) {
            context.setRecommendationId(null); context.setRunId(null); context.setActionId(null); context.setTaskId(null); context.setOutcomeId(null);
        } else if (intent == CopilotIntent.DIAGNOSIS_QUERY) {
            context.setRecommendationId(null); context.setRunId(null); context.setActionId(null); context.setTaskId(null); context.setOutcomeId(null);
        } else if (intent == CopilotIntent.RECOMMENDATION_QUERY) {
            context.setRunId(null); context.setActionId(null); context.setTaskId(null); context.setOutcomeId(null);
        } else if (intent == CopilotIntent.ACTION_REQUEST) {
            context.setTaskId(null); context.setOutcomeId(null);
        } else if (intent == CopilotIntent.OPERATIONS_STATUS_QUERY) {
            context.setFarmId(null); context.setFieldId(null); context.setDiagnosisId(null); context.setRecommendationId(null); context.setRunId(null); context.setActionId(null); context.setOutcomeId(null);
        } else if (intent == CopilotIntent.OUTCOME_QUERY) {
            context.setFarmId(null); context.setFieldId(null); context.setDiagnosisId(null); context.setRecommendationId(null); context.setRunId(null); context.setActionId(null);
        } else if (intent == CopilotIntent.KNOWLEDGE_QUERY) {
            // A knowledge question may still benefit from the selected field's
            // private facts. The IDs have already passed owner validation above;
            // retaining them does not make client-supplied context trusted.
            context.setDiagnosisId(null); context.setRecommendationId(null); context.setRunId(null); context.setActionId(null); context.setTaskId(null); context.setOutcomeId(null);
        }
    }
    private boolean firstRecommendation(String message) { return message != null && (message.contains("第一条") || message.toLowerCase().contains("first recommendation")); }
    private String firstRecommendationId(DiagnosisRecord record) {
        if (record.getPayload() == null || record.getPayload().getRecommendations() == null || record.getPayload().getRecommendations().isEmpty()) return null;
        var recommendation = record.getPayload().getRecommendations().get(0);
        return recommendation == null ? null : recommendation.getId();
    }
    private String route(String message) { return new CopilotIntentRouter().classify(message).safeRoute(); }
    private String find(Pattern pattern, String value) { if (value == null) return null; Matcher matcher = pattern.matcher(value); return matcher.find() ? matcher.group() : null; }
    private String firstNonBlank(String... values) { for (String value : values) if (!blank(value)) return value.trim(); return null; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private Resolution clarify(String question) { return new Resolution(CopilotResolutionStatus.CLARIFICATION_REQUIRED, null, question); }
    private Resolution clarify(String question, CopilotStableContext context) { return new Resolution(CopilotResolutionStatus.CLARIFICATION_REQUIRED, context.copy(), question); }
    private Resolution forbidden() { return new Resolution(CopilotResolutionStatus.FORBIDDEN, null, "当前账号无权访问该上下文。"); }
    private Resolution notFound() { return new Resolution(CopilotResolutionStatus.NOT_FOUND, null, "找不到对应的业务上下文。"); }
    private Resolution stale(String question) { return new Resolution(CopilotResolutionStatus.STALE, null, question); }

    public record Resolution(CopilotResolutionStatus status, CopilotStableContext context, String question) {}
}
