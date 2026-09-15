package com.mei.zhgy.service.copilot.intent;

import com.mei.zhgy.service.ai.AiTaskType;
import com.mei.zhgy.service.ai.BailianModelClient;
import com.mei.zhgy.service.ai.ModelCallRequest;
import com.mei.zhgy.service.ai.ModelCallResult;
import com.mei.zhgy.service.ai.ModelRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Intent transport using the existing non-streaming Bailian client only. */
@Component
public class BailianIntentClassifierClient implements IntentClassifierClient {
    private final BailianModelClient bailian;
    private final IntentAdapter specialized = new SpecializedIntentAdapter();
    private final IntentAdapter flash = new FlashChatAdapter();

    @Autowired
    public BailianIntentClassifierClient(BailianModelClient bailian) {
        this.bailian = bailian;
    }

    @Override
    public RawIntentResponse classify(String model, String tier, IntentPromptBuilder.Prompt prompt, int maxTokens) {
        long started = System.currentTimeMillis();
        IntentAdapter adapter = "PRIMARY".equalsIgnoreCase(tier) ? specialized : flash;
        ModelCallResult result = adapter.call(model, prompt, maxTokens);
        String selected = result.getSelectedModel() == null ? model : result.getSelectedModel();
        return new RawIntentResponse(result.getContent(), selected, tier, System.currentTimeMillis() - started);
    }

    private interface IntentAdapter {
        ModelCallResult call(String model, IntentPromptBuilder.Prompt prompt, int maxTokens);
    }

    private class SpecializedIntentAdapter implements IntentAdapter {
        @Override
        public ModelCallResult call(String model, IntentPromptBuilder.Prompt prompt, int maxTokens) {
            return invoke(model, prompt, maxTokens);
        }
    }

    private class FlashChatAdapter implements IntentAdapter {
        @Override
        public ModelCallResult call(String model, IntentPromptBuilder.Prompt prompt, int maxTokens) {
            return invoke(model, prompt, maxTokens);
        }
    }

    private ModelCallResult invoke(String model, IntentPromptBuilder.Prompt prompt, int maxTokens) {
        ModelCallRequest request = ModelCallRequest.builder()
                .taskType(AiTaskType.COPILOT)
                .systemPrompt(prompt.system())
                .userPrompt(prompt.user())
                .structuredOutput(true)
                .context(java.util.Map.of("maxOutputTokens", Math.max(256, Math.min(300, maxTokens)), "enableThinking", false, "temperature", 0D))
                .build();
        // Intent routing must not inherit the general model gateway's retry
        // policy; the classifier owns one shared deadline and retry decision.
        return bailian.callOnce(model, request, ModelRole.DEFAULT_MODEL);
    }
}
