package com.mei.zhgy.service.ai;

import com.mei.zhgy.ZhgyApplication;
import com.mei.zhgy.config.AnswerPoolProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in, serial entitlement smoke test for every configured answer-pool model. */
class AnswerPoolSmokeTest {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    void probesConfiguredAnswerPoolSeriallyWhenOptedIn() throws Exception {
        if (!"true".equalsIgnoreCase(System.getenv("FARMAP_ANSWER_POOL_SMOKE"))) return;

        Path report = reportPath();
        List<SmokeRow> rows = new ArrayList<>();
        ConfigurableApplicationContext context = null;
        try {
            context = new SpringApplicationBuilder(ZhgyApplication.class)
                    .web(WebApplicationType.NONE)
                    .properties("spring.main.lazy-initialization=true")
                    .run();
            AnswerPoolProperties properties = context.getBean(AnswerPoolProperties.class);
            BailianModelClient client = context.getBean(BailianModelClient.class);
            List<String> models = properties.getModels();
            if (!client.isConfigured()) {
                for (String model : models) rows.add(new SmokeRow(model, "not_run", "missing_api_key", null));
                writeReport(report, models, rows,
                        "未执行真实请求：当前执行环境没有配置百炼凭据。不能据此判断任何模型权限，也不会自动删模型或开启模型池。", null);
                return;
            }
            for (int index = 0; index < models.size(); index++) {
                String model = models.get(index);
                long started = System.nanoTime();
                try {
                    client.probe(model);
                    rows.add(new SmokeRow(model, "ok", "ok", elapsedMs(started)));
                } catch (ModelCallException error) {
                    rows.add(new SmokeRow(model, "failed", error.getErrorType(), elapsedMs(started)));
                } catch (RuntimeException error) {
                    rows.add(new SmokeRow(model, "failed", "provider_error", elapsedMs(started)));
                }
                if (index + 1 < models.size()) pauseBetweenModels();
            }
            writeReport(report, models, rows,
                    "已通过 BailianModelClient 串行完成池内模型最小请求冒烟。", null);
        } catch (RuntimeException error) {
            writeReport(report, List.of(), rows,
                    "冒烟未能启动应用上下文，未对任何模型发起请求；因此不会修改模型池。",
                    error.getClass().getSimpleName());
            throw error;
        } finally {
            if (context != null) context.close();
        }
    }

    private void pauseBetweenModels() {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("answer-pool smoke interrupted", interrupted);
        }
    }

    private long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private Path reportPath() throws IOException {
        Path direct = Paths.get("docs").toAbsolutePath().normalize();
        if (Files.isDirectory(direct)) return direct.resolve("answer-pool-smoke-result.md");
        Path moduleRelative = Paths.get("..", "..", "docs").toAbsolutePath().normalize();
        Files.createDirectories(moduleRelative);
        return moduleRelative.resolve("answer-pool-smoke-result.md");
    }

    private void writeReport(Path report, List<String> models, List<SmokeRow> rows,
                             String conclusion, String contextFailure) throws IOException {
        Files.createDirectories(report.getParent());
        Map<String, Long> counts = new LinkedHashMap<>();
        for (SmokeRow row : rows) counts.merge(row.errorType(), 1L, Long::sum);
        long available = counts.getOrDefault("ok", 0L);
        StringBuilder output = new StringBuilder();
        output.append("# FarMap answer-pool 全池冒烟结果\n\n")
                .append("- 执行时间：").append(LocalDateTime.now().format(TIME)).append("\n")
                .append("- 请求方式：通过 `BailianModelClient.probe` 串行调用；固定中文短问；`max_tokens=1`；不记录响应正文。\n")
                .append("- 配置池模型数：").append(models.size()).append("\n")
                .append("- 结论：").append(conclusion).append("\n");
        if (contextFailure != null) output.append("- 上下文异常类型：").append(contextFailure).append("\n");
        output.append("\n## 汇总\n\n")
                .append("- 可用：").append(available).append("/").append(models.size()).append("\n")
                .append("- 无权限（permission_denied）：").append(joinModels(rows, "permission_denied")).append("\n")
                .append("- 不存在（model_not_found）：").append(joinModels(rows, "model_not_found")).append("\n")
                .append("- 超时（timeout）：").append(joinModels(rows, "timeout")).append("\n")
                .append("- 其他结果：").append(otherSummary(rows)).append("\n");

        output.append("\n## 逐模型结果\n\n")
                .append("| # | 模型 ID | 结果 | errorType | 延迟 ms |\n")
                .append("|---:|---|---|---|---:|\n");
        for (int index = 0; index < rows.size(); index++) {
            SmokeRow row = rows.get(index);
            output.append('|').append(index + 1).append('|').append(row.model())
                    .append('|').append(row.result()).append('|').append(row.errorType())
                    .append('|').append(row.latencyMs() == null ? "-" : row.latencyMs()).append("|\n");
        }
        Files.writeString(report, output.toString(), StandardCharsets.UTF_8);
    }

    private String joinModels(List<SmokeRow> rows, String errorType) {
        List<String> matched = rows.stream().filter(row -> errorType.equals(row.errorType()))
                .map(SmokeRow::model).toList();
        return matched.isEmpty() ? "无" : String.join(", ", matched);
    }

    private String otherSummary(List<SmokeRow> rows) {
        Map<String, Long> other = new LinkedHashMap<>();
        for (SmokeRow row : rows) {
            if (!List.of("ok", "permission_denied", "model_not_found", "timeout").contains(row.errorType())) {
                other.merge(row.errorType(), 1L, Long::sum);
            }
        }
        if (other.isEmpty()) return "无";
        return other.toString();
    }

    private record SmokeRow(String model, String result, String errorType, Long latencyMs) {
    }
}
