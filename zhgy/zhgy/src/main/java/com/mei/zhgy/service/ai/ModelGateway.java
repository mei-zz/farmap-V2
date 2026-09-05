package com.mei.zhgy.service.ai;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 所有新业务通过此门面调用模型，统一处理路由、有限 fallback 和研究元数据。 */
@Service
public class ModelGateway {
    private final ModelRouter modelRouter;
    private final ModelCapabilityRegistry capabilityRegistry;
    private final ModelAvailabilityRegistry availabilityRegistry;
    private final BailianModelClient bailianModelClient;
    private final ModelUsageRecorder usageRecorder;

    public ModelGateway(ModelRouter modelRouter,
                        ModelCapabilityRegistry capabilityRegistry,
                        ModelAvailabilityRegistry availabilityRegistry,
                        BailianModelClient bailianModelClient,
                        ModelUsageRecorder usageRecorder) {
        this.modelRouter = modelRouter;
        this.capabilityRegistry = capabilityRegistry;
        this.availabilityRegistry = availabilityRegistry;
        this.bailianModelClient = bailianModelClient;
        this.usageRecorder = usageRecorder;
    }

    public ModelCallResult complete(ModelCallRequest request) {
        AiTaskType taskType = request.getTaskType() == null ? AiTaskType.GROUNDED_GENERATION : request.getTaskType();
        ModelRoutingDecision decision = modelRouter.route(taskType, toRoutingContext(request));
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(decision.getRequestedModel());
        candidates.addAll(decision.getFallbackModels());
        List<String> attempted = new ArrayList<>();
        ModelCallException lastException = null;

        for (String model : candidates.stream().limit(3).collect(Collectors.toList())) {
            if ("unavailable".equals(availabilityRegistry.status(model))) continue;
            attempted.add(model);
            ModelDefinition definition = capabilityRegistry.get(model);
            try {
                ModelCallResult result = bailianModelClient.call(model, request,
                        definition == null ? ModelRole.DEFAULT_MODEL : definition.getRole());
                availabilityRegistry.markAvailable(model, result.getLatencyMs() == null ? 0 : result.getLatencyMs());
                result.setRequestedModel(decision.getRequestedModel());
                result.setSelectedModel(model);
                result.setRoutingReason(decision.getRoutingReason());
                result.setEscalated(decision.isEscalated());
                result.setFallbackChain(new ArrayList<>(attempted));
                record(request, taskType, decision, result, attempted, null);
                return result;
            } catch (ModelCallException exception) {
                lastException = exception;
                if ("permission_denied".equals(exception.getErrorType())
                        || "model_not_found".equals(exception.getErrorType())) {
                    availabilityRegistry.markUnavailable(model, exception.getErrorType());
                }
            }
        }
        String errorType = lastException == null ? "no_available_model" : lastException.getErrorType();
        String errorMessage = lastException == null ? "没有可用的百炼模型" : lastException.getMessage();
        ModelCallResult failure = ModelCallResult.builder()
                .success(false)
                .requestedModel(decision.getRequestedModel())
                .modelRole(capabilityRegistry.get(decision.getRequestedModel()) == null
                        ? ModelRole.DEFAULT_MODEL : capabilityRegistry.get(decision.getRequestedModel()).getRole())
                .routingReason(decision.getRoutingReason())
                .escalated(decision.isEscalated())
                .fallbackChain(attempted)
                .errorType(errorType)
                .errorMessage(errorMessage)
                .build();
        record(request, taskType, decision, failure, attempted, errorType);
        throw new ModelCallException(errorType, errorMessage);
    }

    private ModelRoutingContext toRoutingContext(ModelCallRequest request) {
        Object imageCount = request.getContext() == null ? null : request.getContext().get("imageCount");
        int count = imageCount instanceof Number ? ((Number) imageCount).intValue() : request.getImageUrls() == null ? 0 : request.getImageUrls().size();
        Object modelPolicy = request.getContext() == null ? null : request.getContext().get("modelPolicy");
        return ModelRoutingContext.builder().imageCount(count).modelPolicy(modelPolicy == null ? "default" : String.valueOf(modelPolicy)).build();
    }

    private void record(ModelCallRequest request,
                        AiTaskType taskType,
                        ModelRoutingDecision decision,
                        ModelCallResult result,
                        List<String> attempted,
                        String errorType) {
        ModelUsageMetadata usage = result.getUsage();
        usageRecorder.record(ModelUsageRecord.builder()
                .requestId(request.getRequestId())
                .runId(request.getRunId())
                .taskType(taskType)
                .requestedModel(decision.getRequestedModel())
                .selectedModel(result.getSelectedModel())
                .fallbackChain(new ArrayList<>(attempted))
                .modelRole(result.getModelRole())
                .routingReason(decision.getRoutingReason())
                .escalated(decision.isEscalated())
                .timestamp(Instant.now().toString())
                .inputModalities(inputModalities(request))
                .latencyMs(result.getLatencyMs())
                .success(result.isSuccess())
                .errorType(errorType)
                .inputTokens(usage == null ? null : usage.getInputTokens())
                .outputTokens(usage == null ? null : usage.getOutputTokens())
                .thinkingTokens(usage == null ? null : usage.getThinkingTokens())
                .totalTokens(usage == null ? null : usage.getTotalTokens())
                .build());
    }

    private List<String> inputModalities(ModelCallRequest request) {
        List<String> modalities = new ArrayList<>();
        if (request.getImageUrls() != null && !request.getImageUrls().isEmpty()) modalities.add("camera");
        if (request.getContext() != null) {
            if (request.getContext().containsKey("weather")) modalities.add("weather");
            if (request.getContext().containsKey("knowledge")) modalities.add("knowledge");
            if (request.getContext().containsKey("historical")) modalities.add("historical_case");
            if (request.getContext().containsKey("soil")) modalities.add("soil");
            if (request.getContext().containsKey("spatial")) modalities.add("spatial");
        }
        return modalities;
    }
}
