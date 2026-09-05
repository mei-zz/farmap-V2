package com.mei.zhgy.service.historical;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Explicit CLI entry point used by scripts/init-historical-vector-store.ps1. */
public final class HistoricalVectorStoreInitializerCli {
    private HistoricalVectorStoreInitializerCli() {}

    public static void main(String[] args) {
        boolean execute = false;
        for (String arg : args) {
            if ("--execute".equalsIgnoreCase(arg)) execute = true;
            else if ("--dry-run".equalsIgnoreCase(arg)) execute = false;
        }
        String host = env("MILVUS_HOST", "127.0.0.1");
        int port = Integer.parseInt(env("MILVUS_PORT", "19530"));
        String username = System.getenv("MILVUS_USERNAME");
        String password = System.getenv("MILVUS_PASSWORD");
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(host).withPort(port)
                .withConnectTimeout(30, TimeUnit.SECONDS)
                .withKeepAliveTime(30, TimeUnit.SECONDS)
                .withKeepAliveTimeout(30, TimeUnit.SECONDS)
                .withRpcDeadline(30, TimeUnit.SECONDS);
        if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            builder.withAuthorization(username, password);
        }
        MilvusServiceClient client = new MilvusServiceClient(builder.build());
        try {
            Map<String, Object> report = new HistoricalVectorStoreInitializer(client).initialize(execute);
            report.forEach((key, value) -> System.out.println("HISTORICAL_VECTOR_STORE " + key + "=" + value));
        } finally {
            try { client.close(5000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return StringUtils.hasText(value) ? value : fallback;
    }
}
