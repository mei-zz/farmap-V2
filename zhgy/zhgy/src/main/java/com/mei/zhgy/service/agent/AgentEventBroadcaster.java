package com.mei.zhgy.service.agent;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-process delivery channel for Agent trace events.
 *
 * <p>The run repository remains the source of truth. This service only keeps
 * live HTTP subscribers so a browser can observe the same trace while a run
 * is executing.</p>
 */
@Service
public class AgentEventBroadcaster {
    private final Map<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public void subscribe(String runId, SseEmitter emitter) {
        subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArraySet<>()).add(emitter);
        Runnable cleanup = () -> remove(runId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
    }

    public void replay(String runId, SseEmitter emitter, List<AgentEvent> events) throws IOException {
        if (events == null) return;
        synchronized (emitter) {
            for (AgentEvent event : events) send(emitter, event);
        }
    }

    /** Completes a stream that was opened after the run had already ended. */
    public void completeIfTerminal(String runId, SseEmitter emitter, RunStatus status) {
        if (!isTerminal(status)) return;
        synchronized (emitter) {
            try {
                sendDone(emitter, runId, status);
                emitter.complete();
            } catch (IOException error) {
                emitter.completeWithError(error);
            }
        }
        remove(runId, emitter);
    }

    public void publish(String runId, AgentEvent event) {
        if (event == null) return;
        Set<SseEmitter> active = subscribers.get(runId);
        if (active == null) return;
        for (SseEmitter emitter : active) {
            synchronized (emitter) {
                try {
                    send(emitter, event);
                } catch (IOException error) {
                    remove(runId, emitter);
                    emitter.completeWithError(error);
                }
            }
        }
    }

    /** Called after the authoritative run snapshot has been persisted. */
    public void complete(String runId, RunStatus status) {
        if (!isTerminal(status)) return;
        Set<SseEmitter> active = subscribers.get(runId);
        if (active == null) return;
        for (SseEmitter emitter : active) {
            synchronized (emitter) {
                try {
                    sendDone(emitter, runId, status);
                    emitter.complete();
                } catch (IOException error) {
                    emitter.completeWithError(error);
                } finally {
                    remove(runId, emitter);
                }
            }
        }
    }

    private void send(SseEmitter emitter, AgentEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(String.valueOf(event.getSequence()))
                .name(event.getType())
                .data(event));
    }

    private void sendDone(SseEmitter emitter, String runId, RunStatus status) throws IOException {
        emitter.send(SseEmitter.event().name("done").data(Map.of(
                "runId", runId,
                "status", status.name())));
    }

    private boolean isTerminal(RunStatus status) {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED
                || status == RunStatus.WAITING_FOR_APPROVAL || status == RunStatus.CANCELLED;
    }

    private void remove(String runId, SseEmitter emitter) {
        Set<SseEmitter> active = subscribers.get(runId);
        if (active == null) return;
        active.remove(emitter);
        if (active.isEmpty()) subscribers.remove(runId, active);
    }
}
