package dev.ngb.core.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.broker.EnvelopeCodec;
import dev.ngb.core.messaging.broker.EventDocument;
import dev.ngb.core.messaging.config.MessagingProperties;
import dev.ngb.core.messaging.config.MessagingProperties.Document;
import dev.ngb.core.messaging.config.MessagingProperties.QueueBinding;
import dev.ngb.core.messaging.config.MessagingProperties.Relay;
import dev.ngb.core.messaging.inbox.JooqEventInbox;
import dev.ngb.core.messaging.topology.SuiteTopology;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.spring.tenancy.ScopedTenantContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.retry.support.RetryTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

/** The inbound path: envelope decoding, scope, inbox dedupe, retries and the dead-letter queue. */
class AmqpInboundDispatcherIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");
    private static final String EXCHANGE = "suite.test";
    private static final String QUEUE = "test.inbound";

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    private final ProbeHandler handler = new ProbeHandler();
    private final JsonMapper json = JsonMapper.builder().build();
    private CachingConnectionFactory connectionFactory;
    private RabbitTemplate rabbit;
    private SimpleMessageListenerContainer container;
    private AmqpInboundDispatcher dispatcher;

    @BeforeEach
    void setUp() throws SQLException {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("drop table if exists event_inbox");
            statement.execute("""
                    create table event_inbox (
                        tenant_id    uuid        not null,
                        consumer     text        not null,
                        event_id     uuid        not null,
                        processed_at timestamptz not null default now(),
                        primary key (tenant_id, consumer, event_id)
                    )""");
        }
        DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
        UnitOfWork unitOfWork = new SpringUnitOfWork(new DataSourceTransactionManager(dataSource), TransactionScopeHook.none());

        connectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());
        rabbit = new RabbitTemplate(connectionFactory);

        MessagingProperties properties = new MessagingProperties(
                EXCHANGE, Map.of("test.event", EXCHANGE), List.of(new QueueBinding(QUEUE, EXCHANGE, List.of("#"))),
                new Relay(10, 3, Duration.ofMillis(10), Duration.ofMillis(20), 0.2, Duration.ofSeconds(30)),
                new Document(EventDocument.SCHEMA_VERSION));
        declare(properties);

        dispatcher = new AmqpInboundDispatcher(
                new EnvelopeCodec(json), List.of(handler), new JooqEventInbox(dsl), unitOfWork, new ScopedTenantContext(),
                RetryTemplate.builder().maxAttempts(3).fixedBackoff(1).build());
        container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(QUEUE);
        container.setMessageListener(message -> dispatcher.onMessage(message.getBody()));
        container.start();
        rabbit.receive(QUEUE, 100);
        rabbit.receive(QUEUE + ".dlq", 100);
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void aDuplicateDeliveryRunsTheHandlerOnce() {
        byte[] body = envelope("test.event");
        send(body);
        send(body);

        await(() -> handler.attempts >= 2, 5_000);
        assertThat(handler.handled).hasSize(1);
    }

    @Test
    void aHandlerThatAlwaysThrowsDeadLettersAfterThreeAttempts() {
        handler.failing = true;
        send(envelope("test.event"));

        Message dead = rabbit.receive(QUEUE + ".dlq", 10_000);
        assertThat(dead).as("attempts=%s running=%s queue=%s dlq=%s", handler.attempts, container.isRunning(),
                rabbit.execute(channel -> channel.messageCount(QUEUE)),
                rabbit.execute(channel -> channel.messageCount(QUEUE + ".dlq"))).isNotNull();
        assertThat(handler.attempts).isEqualTo(3);
        assertThat(handler.handled).isEmpty();
    }

    @Test
    void aBodyThatIsNotAnEnvelopeIsDeadLetteredAtOnceNotRequeued() {
        send("not json at all".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(rabbit.receive(QUEUE + ".dlq", 10_000)).isNotNull();
        Long queued = rabbit.execute(channel -> channel.messageCount(QUEUE));
        assertThat(queued).isZero();
        assertThat(handler.attempts).isZero();
    }

    @Test
    void anUnsubscribedTypeIsAcknowledgedAndIgnored() {
        send(envelope("other.event"));

        await(() -> false, 500);
        assertThat(rabbit.receive(QUEUE + ".dlq", 500)).isNull();
        assertThat(handler.attempts).isZero();
        assertThat(handler.handled).isEmpty();
    }

    private static void await(java.util.function.BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void send(byte[] body) {
        rabbit.send(EXCHANGE, "test.event", MessageBuilder.withBody(body).setContentType("application/json").build());
    }

    private byte[] envelope(String type) {
        UUID id = UUID.randomUUID();
        TenantId tenant = new TenantId(UUID.randomUUID());
        return new EnvelopeCodec(json).encode(
                new EventEnvelope(id, tenant, type, "test/" + id, Instant.now(), Map.of("n", 1)));
    }

    private void declare(MessagingProperties properties) {
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

    static final class ProbeHandler implements InboundEventHandler {
        final List<UUID> handled = new CopyOnWriteArrayList<>();
        volatile int attempts;
        volatile boolean failing;

        @Override
        public String consumer() {
            return "probe";
        }

        @Override
        public boolean accepts(String type) {
            return type.equals("test.event");
        }

        @Override
        public void handle(EventEnvelope event) {
            attempts++;
            if (failing) {
                throw new IllegalStateException("consumer exploded");
            }
            handled.add(event.eventId());
        }
    }
}
