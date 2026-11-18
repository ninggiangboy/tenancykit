package dev.ngb.core.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.runtime.SystemClock;
import dev.ngb.core.util.Backoff;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** The outbox, inbox and relay against the documented table contract, without any service's generated schema. */
class OutboxRelayIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    private final List<EventEnvelope> seen = new ArrayList<>();
    private final AtomicBoolean failing = new AtomicBoolean(false);
    private final AtomicBoolean contended = new AtomicBoolean(false);

    private PGSimpleDataSource dataSource;
    private DSLContext dsl;
    private UnitOfWork unitOfWork;
    private EventPublisher events;
    private OutboxRelay relay;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists outbox");
            statement.execute("drop table if exists event_inbox");
            statement.execute("""
                    create table outbox (
                        id              uuid primary key,
                        tenant_id       uuid        not null,
                        aggregate       text        not null,
                        type            text        not null,
                        payload         jsonb       not null,
                        created_at      timestamptz not null,
                        status          text        not null default 'PENDING' check (status in ('PENDING', 'DELIVERED', 'DEAD')),
                        attempts        integer     not null default 0,
                        next_attempt_at timestamptz not null,
                        last_error      text
                    )""");
            statement.execute("""
                    create table event_inbox (
                        tenant_id    uuid        not null,
                        consumer     text        not null,
                        event_id     uuid        not null,
                        processed_at timestamptz not null default now(),
                        primary key (tenant_id, consumer, event_id)
                    )""");
        }
        dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
        unitOfWork = new SpringUnitOfWork(new DataSourceTransactionManager(dataSource), TransactionScopeHook.none());
        events = new JooqOutboxAppender(dsl, JsonMapper.builder().build());
        ApplicationEventPublisher publisher = event -> {
            EventEnvelope envelope = (EventEnvelope) event;
            if (failing.get() && envelope.type().equals("test.poison")) {
                throw new IllegalStateException("consumer exploded");
            }
            if (contended.get() && envelope.type().equals("test.contended")) {
                throw new IllegalStateException("the row lock timed out",
                        new SQLException("canceling statement due to lock timeout", "55P03"));
            }
            dsl.insertInto(MessagingTables.EVENT_INBOX)
                    .set(MessagingTables.EVENT_INBOX_TENANT_ID, envelope.tenantId().value())
                    .set(MessagingTables.EVENT_INBOX_CONSUMER, "probe")
                    .set(MessagingTables.EVENT_INBOX_EVENT_ID, envelope.eventId())
                    .onConflict(
                            MessagingTables.EVENT_INBOX_TENANT_ID,
                            MessagingTables.EVENT_INBOX_CONSUMER,
                            MessagingTables.EVENT_INBOX_EVENT_ID)
                    .doNothing()
                    .execute();
            seen.add(envelope);
        };
        relay = new OutboxRelay(
                unitOfWork, dsl, JsonMapper.builder().build(), publisher, new SystemClock(), new SimpleMeterRegistry(), "core-test",
                1, 2, new Backoff(Duration.ofMillis(1), Duration.ofMillis(2), 0.2));
        seen.clear();
    }

    @Test
    void aDeliveredEventIsMarkedAndNotRelayedAgain() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        UUID id = publish(tenant, "test.fine");

        assertThat(relay.relayDue()).isEqualTo(new OutboxRelay.Result(1, 0, 0));
        assertThat(statusOf(id)).containsEntry("status", "DELIVERED");
        assertThat(seen).extracting(EventEnvelope::eventId).containsExactly(id);
        assertThat(relay.relayDue()).isEqualTo(new OutboxRelay.Result(0, 0, 0));
    }

    @Test
    void aFailingConsumerRollsBackItsWorkAndTheEventIsDeadLetteredAfterTheAttemptLimit() throws InterruptedException {
        TenantId tenant = new TenantId(UUID.randomUUID());
        failing.set(true);
        UUID poison = publish(tenant, "test.poison");

        assertThat(relay.relayDue()).isEqualTo(new OutboxRelay.Result(0, 1, 0));
        assertThat(statusOf(poison)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        Thread.sleep(10);
        assertThat(relay.relayDue()).isEqualTo(new OutboxRelay.Result(0, 0, 1));
        assertThat(statusOf(poison)).containsEntry("status", "DEAD").containsEntry("attempts", 2);
        assertThat(seen).isEmpty();
    }

    @Test
    void aLockTimeoutSpendsNoAttemptAndLeavesTheEventDue() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        UUID contendedEvent = publish(tenant, "test.contended");
        contended.set(true);
        try {
            assertThat(relay.relayDue()).as("contention is neither a delivery nor a failure").isEqualTo(new OutboxRelay.Result(0, 0, 0));
            assertThat(statusOf(contendedEvent)).containsEntry("status", "PENDING").containsEntry("attempts", 0);
            assertThat(seen).isEmpty();

            contended.set(false);
            assertThat(relay.relayDue().delivered()).as("the next poll delivers it").isEqualTo(1);
            assertThat(statusOf(contendedEvent)).containsEntry("status", "DELIVERED");
        } finally {
            contended.set(false);
        }
    }

    private UUID publish(TenantId tenant, String type) {
        UUID id = UUID.randomUUID();
        unitOfWork.transactional(() -> events.publish(new ProbeEvent(id, tenant, type, "test/" + id, Instant.now())));
        return id;
    }

    private Map<String, Object> statusOf(UUID id) {
        return dsl.select(MessagingTables.OUTBOX_STATUS, MessagingTables.OUTBOX_ATTEMPTS)
                .from(MessagingTables.OUTBOX)
                .where(MessagingTables.OUTBOX_ID.eq(id))
                .fetchOne(record -> Map.of(
                        "status", record.get(MessagingTables.OUTBOX_STATUS),
                        "attempts", record.get(MessagingTables.OUTBOX_ATTEMPTS)));
    }

    private record ProbeEvent(UUID eventId, TenantId tenantId, String type, String aggregate, Instant occurredAt) implements DomainEvent {

        @Override
        public Map<String, Object> payload() {
            return Map.of("n", 1);
        }
    }
}
