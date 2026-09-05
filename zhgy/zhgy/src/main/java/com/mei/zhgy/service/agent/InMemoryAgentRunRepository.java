package com.mei.zhgy.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Repository boundary keeps the runtime storage-neutral; MySQL migration can replace this bean. */
@Repository
public class InMemoryAgentRunRepository implements AgentRunRepository {
    private final ConcurrentHashMap<String, AgentRun> runs = new ConcurrentHashMap<>();
    private final Path store;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public InMemoryAgentRunRepository() {
        store = Path.of(System.getProperty("farmap.agent.store", ".cache/agent-runs"));
        load();
    }

    @Override public AgentRun save(AgentRun run) {
        runs.put(run.getRunId(), run);
        try { Files.createDirectories(store); mapper.writeValue(store.resolve(run.getRunId() + ".json").toFile(), run); }
        catch (Exception ignored) { /* local persistence is best effort; API state remains available */ }
        return run;
    }
    @Override public Optional<AgentRun> find(String runId) { return Optional.ofNullable(runs.get(runId)); }
    @Override public List<AgentRun> findAll() { List<AgentRun> result = new ArrayList<>(runs.values()); result.sort(Comparator.comparing(AgentRun::getCreatedAt).reversed()); return result; }
    private void load() {
        try {
            if (!Files.isDirectory(store)) return;
            try (java.util.stream.Stream<Path> paths = Files.list(store)) {
                paths.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                    try { AgentRun run = mapper.readValue(path.toFile(), AgentRun.class); if (run.getRunId() != null) runs.put(run.getRunId(), run); }
                    catch (Exception ignored) { }
                });
            }
        } catch (Exception ignored) { }
    }
}
