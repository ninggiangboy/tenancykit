package dev.ngb.core.messaging.broker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.event.DomainEvent;
import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.config.MessagingProperties;
import dev.ngb.core.messaging.config.MessagingProperties.QueueBinding;
import dev.ngb.core.messaging.config.MessagingProperties.Relay;
import dev.ngb.core.messaging.outbox.JooqOutboxAppender;
import dev.ngb.core.messaging.outbox.MessagingTables;
import dev.ngb.core.messaging.topology.SuiteTopology;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.runtime.SystemClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

/** The broker path against a real PostgreSQL and RabbitMQ, using only the documented table contract. */
class BrokerRelayIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");
    private static final String QUEUE = "test.events";

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    private PGSimpleDataSource dataSource;
    private DSLContext dsl;
    private UnitOfWork unitOfWork;
    private EventPublisher events;
    private CachingConnectionFactory connectionFactory;
    private RabbitTemplate rabbit;
    private MessagingProperties properties;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists outbox");
            statement.execute("""
                    create table outbox (
                        id               uuid primary key,
                        tenant_id        uuid        not null,
                        aggregate        text        not null,
                        type             text        not null,
                        payload          jsonb       not null,
                        created_at       timestamptz not null,
                        status           text        not null default 'PENDING' check (status in ('PENDING', 'DELIVERED', 'DEAD')),
                        attempts         integer     not null default 0,
                        next_attempt_at  timestamptz not null,
                        last_error       text,
                        published_at     timestamptz,
                        publish_status   text        not null default 'PENDING'
                            check (publish_status in ('PENDING', 'PUBLISHED', 'DEAD', 'SKIPPED')),
                        publish_attempts integer     not null default 0,
                        publish_next_at  timestamptz not null default now()
                    )""");
        }
        dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
        unitOfWork = new SpringUnitOfWork(new DataSourceTransactionManager(dataSource), TransactionScopeHook.none());
        events = new JooqOutboxAppender(dsl, JsonMapper.builder().build());

        connectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        rabbit = new RabbitTemplate(connectionFactory);

        properties = new MessagingProperties(
                "billing.events",
                Map.of("invoice.created", "billing.events"),
                List.of(new QueueBinding(QUEUE, "billing.events", List.of("#"))),
                new Relay(10, 3, Duration.ofMillis(10), Duration.ofMillis(20)));
        declare();
        rabbit.receive(QUEUE, 200);
    }

    @Test
    void aDueEventIsPublishedOnceWithTheContractEnvelopeAndMarked() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        UUID id = publish(tenant, "invoice.created");

        assertThat(relay(rabbit).relayDue()).isEqualTo(new BrokerRelay.Result(1, 0, 0));

        String body = receive();
        assertThat(body).isNotNull();
        EventEnvelope received = new EnvelopeCodec(JsonMapper.builder().build()).decode(body.getBytes());
        assertThat(received.eventId()).isEqualTo(id);
        assertThat(received.tenantId()).isEqualTo(tenant);
        assertThat(received.type()).isEqualTo("invoice.created");
        assertThat(received.payload()).containsEntry("n", 1);
        assertThat(row(id)).containsEntry("publish_status", "PUBLISHED").containsEntry("publish_attempts", 0);
        assertThat(row(id).get("published_at")).isNotNull();

        assertThat(relay(rabbit).relayDue()).isEqualTo(new BrokerRelay.Result(0, 0, 0));
        assertThat(receive()).isNull();
    }

    @Test
    void aTypeOutsideTheExportsIsNeverPublished() {
        UUID id = publish(new TenantId(UUID.randomUUID()), "internal.only");

        assertThat(relay(rabbit).relayDue()).isEqualTo(new BrokerRelay.Result(0, 0, 0));
        assertThat(receive()).isNull();
        assertThat(row(id)).containsEntry("publish_status", "PENDING");
        assertThat(row(id).get("published_at")).isNull();
    }

    @Test
    void anOutageLeavesTheRowPendingAndItPublishesWhenTheBrokerIsBack() {
        UUID id = publish(new TenantId(UUID.randomUUID()), "invoice.created");

        RabbitTemplate unreachable = new RabbitTemplate(closedConnectionFactory());
        assertThat(relay(unreachable).relayDue()).isEqualTo(new BrokerRelay.Result(0, 1, 0));
        assertThat(row(id)).containsEntry("publish_status", "PENDING").containsEntry("publish_attempts", 1);

        awaitDueAgain(id);
        assertThat(relay(rabbit).relayDue()).isEqualTo(new BrokerRelay.Result(1, 0, 0));
        assertThat(receive()).isNotNull();
        assertThat(row(id)).containsEntry("publish_status", "PUBLISHED");
    }

    @Test
    void aRowThatKeepsFailingBecomesDead() {
        UUID id = publish(new TenantId(UUID.randomUUID()), "invoice.created");
        RabbitTemplate unreachable = new RabbitTemplate(closedConnectionFactory());

        assertThat(relay(unreachable).relayDue()).isEqualTo(new BrokerRelay.Result(0, 1, 0));
        for (int attempt = 2; attempt <= 3; attempt++) {
            awaitDueAgain(id);
            BrokerRelay.Result result = relay(unreachable).relayDue();
            assertThat(result.failed() + result.dead()).isEqualTo(1);
        }
        assertThat(row(id)).containsEntry("publish_status", "DEAD").containsEntry("publish_attempts", 3);
    }

    private BrokerRelay relay(RabbitTemplate template) {
        return new BrokerRelay(
                unitOfWork, dsl, JsonMapper.builder().build(), template, new SystemClock(), new SimpleMeterRegistry(), properties, "core-test");
    }

    private UUID publish(TenantId tenant, String type) {
        UUID id = UUID.randomUUID();
        unitOfWork.transactional(() -> events.publish(new ProbeEvent(id, tenant, type, "test/" + id, Instant.now())));
        return id;
    }

    private void declare() {
        AmqpAdmin admin = new RabbitAdmin(connectionFactory);
        for (Declarable declarable : SuiteTopology.declarables(properties).getDeclarables()) {
            if (declarable instanceof Exchange exchange) {
                admin.declareExchange(exchange);
            } else if (declarable instanceof Queue queue) {
                admin.declareQueue(queue);
            } else if (declarable instanceof Binding binding) {
                admin.declareBinding(binding);
            }
        }
    }

    private CachingConnectionFactory closedConnectionFactory() {
        CachingConnectionFactory closed = new CachingConnectionFactory("localhost", 1);
        closed.setConnectionTimeout(500);
        closed.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        return closed;
    }

    private void awaitDueAgain(UUID id) {
        for (int i = 0; i < 100; i++) {
            if (!"PENDING".equals(row(id).get("publish_status"))) {
                return;
            }
            if (Boolean.TRUE.equals(dsl.select(MessagingTables.OUTBOX_PUBLISH_NEXT_AT.le(java.time.OffsetDateTime.now()))
                    .from(MessagingTables.OUTBOX)
                    .where(MessagingTables.OUTBOX_ID.eq(id))
                    .fetchOne(0, Boolean.class))) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String receive() {
        var message = rabbit.receive(QUEUE, 1_000);
        return message == null ? null : new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private Map<String, Object> row(UUID id) {
        return dsl.select(
                        MessagingTables.OUTBOX_PUBLISH_STATUS,
                        MessagingTables.OUTBOX_PUBLISH_ATTEMPTS,
                        MessagingTables.OUTBOX_PUBLISHED_AT)
                .from(MessagingTables.OUTBOX)
                .where(MessagingTables.OUTBOX_ID.eq(id))
                .fetchOne(record -> {
                    Map<String, Object> values = new java.util.HashMap<>();
                    values.put("publish_status", record.get(MessagingTables.OUTBOX_PUBLISH_STATUS));
                    values.put("publish_attempts", record.get(MessagingTables.OUTBOX_PUBLISH_ATTEMPTS));
                    values.put("published_at", record.get(MessagingTables.OUTBOX_PUBLISHED_AT));
                    return values;
                });
    }

    private record ProbeEvent(UUID eventId, TenantId tenantId, String type, String aggregate, Instant occurredAt) implements DomainEvent {

        @Override
        public Map<String, Object> payload() {
            return Map.of("n", 1);
        }
    }
}
