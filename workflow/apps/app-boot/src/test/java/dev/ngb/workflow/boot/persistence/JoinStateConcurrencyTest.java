package dev.ngb.workflow.boot.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.workflow.support.PostgresContainerConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the join counter is atomic: fifty branches increment one row at the same instant and the counts they see are
 * exactly 1..50, so no arrival is lost and no count is served twice.
 */
class JoinStateConcurrencyTest {

    private static final PostgreSQLContainer POSTGRES = PostgresContainerConfig.postgres();

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .placeholders(java.util.Map.of("appDbPassword", PostgresContainerConfig.APP_PASSWORD))
                .load()
                .migrate();
    }

    @Test
    void fiftyConcurrentArrivalsProduceEveryCountExactlyOnce() throws Exception {
        UUID instance = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        int branches = 50;
        List<Integer> arrived = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch go = new CountDownLatch(1);

        try (ExecutorService workers = Executors.newFixedThreadPool(16)) {
            for (int branch = 0; branch < branches; branch++) {
                workers.submit(() -> {
                    go.await();
                    arrived.add(arrive(instance, tenant, branches));
                    return null;
                });
            }
            go.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(arrived).hasSize(branches);
        List<Integer> counts = arrived.stream().sorted().toList();
        assertThat(counts).isEqualTo(java.util.stream.IntStream.rangeClosed(1, branches).boxed().toList());
    }

    private static int arrive(UUID instance, UUID tenant, int expected) throws SQLException {
        String sql = """
                insert into workflow_join_state
                    (instance_id, node_id, tenant_id, expected, arrived, updated_at)
                values ('%s', 'join', '%s', %d, 1, now())
                on conflict (instance_id, node_id)
                do update set arrived = workflow_join_state.arrived + 1, updated_at = now()
                returning arrived
                """
                .formatted(instance, tenant, expected);
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }
}
