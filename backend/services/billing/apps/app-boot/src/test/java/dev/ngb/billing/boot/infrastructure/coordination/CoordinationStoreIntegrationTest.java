package dev.ngb.billing.boot.infrastructure.coordination;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.Application;
import dev.ngb.billing.support.PostgresContainerConfig;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(classes = Application.class)
@Import(PostgresContainerConfig.class)
class CoordinationStoreIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void aLeaseAdmitsOneOwnerUntilItExpiresOrIsReleased() {
        String name = "lease-" + UUID.randomUUID();
        JobLeases first = leases();
        JobLeases second = leases();

        assertThat(first.tryAcquire(name, Duration.ofMinutes(1))).isTrue();
        assertThat(second.tryAcquire(name, Duration.ofMinutes(1))).as("a second instance is refused").isFalse();
        assertThat(first.renew(name, Duration.ofMinutes(1))).as("the owner extends its lease").isTrue();
        assertThat(second.renew(name, Duration.ofMinutes(1))).as("a stranger cannot extend it").isFalse();

        first.release(name);
        assertThat(second.tryAcquire(name, Duration.ofMinutes(1))).as("release hands it over").isTrue();

        second.release(name);
        first.tryAcquire(name, Duration.ZERO);
        assertThat(second.tryAcquire(name, Duration.ofMinutes(1))).as("an expired lease can be taken").isTrue();
    }

    @Test
    void aRunIsClaimedOncePerKeyAndTenant() {
        JobRuns runs = new JobRuns(transactions, jdbc);
        String job = "run-" + UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        Duration ttl = Duration.ofMinutes(30);

        assertThat(runs.claim(job, "2026-11-11", tenant, ttl)).isTrue();
        assertThat(runs.claim(job, "2026-11-11", tenant, ttl)).as("a repeated claim does nothing").isFalse();
        assertThat(runs.claim(job, "2026-11-11", UUID.randomUUID(), ttl)).as("another tenant has its own run").isTrue();
        assertThat(runs.claim(job, "2026-11-12", tenant, ttl)).as("the next key runs again").isTrue();

        runs.release(job, "2026-11-11", tenant);
        assertThat(runs.claim(job, "2026-11-11", tenant, ttl)).as("a released claim can be taken again").isTrue();
    }

    @Test
    void anUnfinishedClaimIsTakenOverOnlyAfterItsTtlAndAFinishedOneNever() {
        JobRuns runs = new JobRuns(transactions, jdbc);
        String job = "run-" + UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        Duration ttl = Duration.ofMinutes(30);

        assertThat(runs.claim(job, "2026-11-11", tenant, ttl)).isTrue();
        assertThat(runs.claim(job, "2026-11-11", tenant, ttl)).as("a fresh unfinished claim is still owned").isFalse();

        jdbc.update("update job_runs set started_at = now() - interval '31 minutes' where job = ? and run_key = ? and tenant_id = ?",
                job, "2026-11-11", tenant);
        assertThat(runs.claim(job, "2026-11-11", tenant, ttl)).as("a claim left by a dead process is taken over").isTrue();

        runs.complete(job, "2026-11-11", tenant);
        jdbc.update("update job_runs set started_at = now() - interval '31 minutes' where job = ? and run_key = ? and tenant_id = ?",
                job, "2026-11-11", tenant);
        assertThat(runs.claim(job, "2026-11-11", tenant, ttl)).as("a finished run is never repeated").isFalse();
    }




    private JobLeases leases() {
        return new JobLeases(transactions, jdbc);
    }

}
