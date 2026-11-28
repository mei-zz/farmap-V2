package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.agent.AgentAccessException;
import com.mei.zhgy.service.copilot.*;
import com.mei.zhgy.service.copilot.CopilotApplicationService.AnswerRequest;
import com.mei.zhgy.service.copilot.CopilotApplicationService.CopilotChatRequest;
import com.mei.zhgy.service.copilot.CopilotApplicationService.PiControl;
import com.mei.zhgy.service.field.FieldAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/copilot")
public class CopilotController {
    final CopilotApplicationService application;

    @Autowired
    public CopilotController(CopilotApplicationService application) {
        this.application = application;
    }

    @PostMapping("/conversations")
    public CopilotConversationView createConversation() {
        return application.createConversation(actor());
    }

    @GetMapping("/conversations/{conversationId}")
    public CopilotConversationView getConversation(@PathVariable String conversationId) {
        return application.getConversation(conversationId, actor());
    }

    @GetMapping("/conversations")
    public List<CopilotConversationListItem> listConversations(@RequestParam(defaultValue = "24") int limit) {
        return application.listConversations(limit, actor());
    }

    @PostMapping("/conversations/{conversationId}/resume")
    public CopilotConversationView resumeConversation(@PathVariable String conversationId) {
        return application.resumeConversation(conversationId, actor());
    }

    @PostMapping("/route")
    public CopilotRouteResult route(@RequestBody CopilotRouteRequest request) {
        return application.route(request, actor());
    }

    @PostMapping(value = "/conversations/{conversationId}/agent-control", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter controlAgent(@PathVariable String conversationId, @RequestBody PiControl command) {
        SseEmitter stream = emitter(130_000);
        application.controlAgent(conversationId, command, actor(), stream);
        return stream;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody(required = false) CopilotChatRequest request) {
        SseEmitter stream = emitter(30 * 60 * 1000L);
        application.chat(request, actor(), stream);
        return stream;
    }

    @PostMapping("/answer")
    public Result<Map<String, Object>> answer(@RequestBody AnswerRequest request) {
        return application.answer(request, actor());
    }

    @GetMapping("/memories")
    public List<CopilotMemoryView> listMemories(@RequestParam(required = false) String farmId,
                                               @RequestParam(required = false) String fieldId) {
        return application.listMemories(farmId, fieldId, actor());
    }

    @PostMapping("/memories/{memoryId}/revoke")
    public CopilotMemoryView revokeMemory(@PathVariable String memoryId) {
        return application.revokeMemory(memoryId, actor());
    }

    @PostMapping(value = "/memories/proposals/{proposalId}/confirm", produces = "application/json")
    public CopilotMemoryProposalService.ProposalConfirmation confirmMemoryProposal(@PathVariable String proposalId) {
        return application.confirmMemoryProposal(proposalId, actor());
    }

    @PostMapping(value = "/memories/proposals/{proposalId}/ignore", produces = "application/json")
    public CopilotMemoryProposalService.ProposalView ignoreMemoryProposal(@PathVariable String proposalId) {
        return application.ignoreMemoryProposal(proposalId, actor());
    }

    @PostMapping(value = "/task-drafts", produces = "application/json")
    public com.mei.zhgy.service.agent.AgentRun draftTask(@RequestBody CopilotApplicationService.UiTaskDraft command) {
        return application.draftFromComponent(command, actor());
    }

    @PostMapping(value = "/task-drafts/batch", produces = "application/json")
    public java.util.List<com.mei.zhgy.service.agent.AgentRun> draftTasks(@RequestBody CopilotApplicationService.UiTaskBatch command) {
        return application.draftBatchFromComponent(command, actor());
    }

    @PostMapping(value = "/fields/{fieldId}/phenology/calibrate", produces = "application/json")
    public com.mei.zhgy.service.field.FarmField calibratePhenology(@PathVariable String fieldId,
            @RequestBody CopilotApplicationService.PhenologyCalibration command) {
        return application.calibrateField(fieldId, command, actor());
    }

    @GetMapping(value = "/memories/proposals/{proposalId}", produces = "application/json")
    public CopilotMemoryProposalService.ProposalView readMemoryProposal(@PathVariable String proposalId) {
        return application.readMemoryProposal(proposalId, actor());
    }

    @ExceptionHandler(AgentAccessException.class)
    public ResponseEntity<Map<String, Object>> accessError(AgentAccessException error) {
        return error(error.getHttpStatus(), error.getCode(), error.getMessage());
    }

    @ExceptionHandler(CopilotConversationException.class)
    public ResponseEntity<Map<String, Object>> conversationError(CopilotConversationException error) {
        return error(error.getHttpStatus(), error.getCode(), error.getMessage());
    }

    @ExceptionHandler(FieldAccessException.class)
    public ResponseEntity<Map<String, Object>> fieldError(FieldAccessException error) {
        return error(error.getHttpStatus(), error.getCode(), error.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message, "timestamp", Instant.now().toString()));
    }

    private Long actor() {
        Long id = BaseContext.getCurrentId();
        if (id == null) throw AgentAccessException.unauthenticated();
        return id;
    }

    private SseEmitter emitter(long timeoutMs) {
        SseEmitter stream = new SseEmitter(timeoutMs);
        stream.onTimeout(stream::complete);
        stream.onError(ignored -> stream.complete());
        return stream;
    }

}
