package com.mei.zhgy.service.knowledge;

import com.mei.zhgy.vo.KnowledgeGraphResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Read-only contract coverage against the local canonical Knowledge schema. */
class KnowledgeGraphServiceMysqlTest {
    @Test
    void graphUsesStableIdsAndActorScopedDiagnosisRelations() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:mysql://127.0.0.1:3306/zhgy?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
        dataSource.setUsername(System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
        try (Connection ignored = dataSource.getConnection()) {
            // Connection probe only; assertions below must remain real failures.
        } catch (Exception error) {
            Assumptions.assumeTrue(false, "local MySQL is not available for read-only graph contract test");
            return;
        }
        KnowledgeGraphResponse response = new KnowledgeGraphService(dataSource, null, null, null)
                .read(1L, null, null, null, null, 150, true);
            assertTrue(response.nodes().size() > 0, "the real knowledge schema should produce graph nodes");
            assertTrue(response.nodes().size() <= 150, "graph must be bounded");
            assertNotNull(response.summary().sourceCount());
            assertNotNull(response.summary().productionChunkCount());
            Set<String> nodeIds = new HashSet<>(response.nodes().stream().map(KnowledgeGraphResponse.Node::id).toList());
            response.edges().forEach(edge -> {
                assertTrue(nodeIds.contains(edge.source()), "edge source must be a returned stable node");
                assertTrue(nodeIds.contains(edge.target()), "edge target must be a returned stable node");
                assertFalse(edge.source().isBlank());
                assertFalse(edge.target().isBlank());
            });
            response.nodes().stream().filter(node -> "DIAGNOSIS".equals(node.type())).forEach(node -> {
                assertEquals(1, node.metadata().get("farmId"), "user 1 graph must not expose another actor's farm diagnosis");
            });
    }
}
