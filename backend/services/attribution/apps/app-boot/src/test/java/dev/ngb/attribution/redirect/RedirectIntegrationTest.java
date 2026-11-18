package dev.ngb.attribution.redirect;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.attribution.links.adapter.redirect.cache.LinkCache;
import dev.ngb.attribution.links.adapter.redirect.ratelimit.PublicRateLimiter;
import dev.ngb.attribution.links.adapter.redirect.suspect.SuspectTracker;
import dev.ngb.attribution.links.adapter.redirect.unlock.UnlockAttempts;
import dev.ngb.attribution.links.adapter.redirect.web.ClickFactory;
import dev.ngb.attribution.boot.web.RedirectController;
import dev.ngb.attribution.links.adapter.link.persistence.JooqLinkRepository;
import dev.ngb.attribution.links.application.redirect.service.RedirectDecider;
import dev.ngb.attribution.links.domain.link.Destination;
import dev.ngb.attribution.links.domain.link.DestinationUrl;
import dev.ngb.attribution.links.domain.link.DeviceMatch;
import dev.ngb.attribution.links.domain.link.Link;
import dev.ngb.attribution.links.domain.link.LinkKind;
import dev.ngb.attribution.links.domain.link.Slug;
import dev.ngb.attribution.tracking.adapter.ingest.amqp.ClickPublisher;
import dev.ngb.attribution.tracking.adapter.ingest.memory.IngestBuffer;
import dev.ngb.attribution.support.PostgresContainerConfig;
import dev.ngb.attribution.support.RabbitContainerConfig;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.TransactionScopeHook;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.spring.runtime.SpringUnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.visitor.DailyVisitorHash;
import dev.ngb.core.visitor.GeoCountryReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The redirect's two claims that only real infrastructure can prove: a cap of N yields exactly N counted clicks under
 * 100 parallel requests, and 2,000 clicks produce 2,000 messages on the ingest queue, each once.
 *
 * <p>The link cache, the decider and the store are the production classes against the real migrations, read as
 * {@code attribution_app} so row-level security is in force. Only the unlock limiter's timing and the entitlement gate
 * are stood in for, because neither is what this test is about.
 */
class RedirectIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresContainerConfig.IMAGE);
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");
    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final Map<String, String> CLICK_KEYS = Map.of("k1", "test-click-secret");

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final FixedTenantContext tenantContext = new FixedTenantContext();
    private final JsonMapper json = new JsonMapper();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private DSLContext dsl;
    private UnitOfWork unitOfWork;
    private JooqLinkRepository links;
    private CachingConnectionFactory connectionFactory;
    private AmqpAdmin admin;
    private RabbitTemplate rabbit;

    @AfterAll
    static void stop() {
        RABBIT.stop();
    }

    @BeforeEach
    void setUp() {
        PGSimpleDataSource owner = new PGSimpleDataSource();
        owner.setUrl(POSTGRES.getJdbcUrl());
        owner.setUser(POSTGRES.getUsername());
        owner.setPassword(POSTGRES.getPassword());
        DSL.using(owner, SQLDialect.POSTGRES).execute("drop schema if exists public cascade");
        DSL.using(owner, SQLDialect.POSTGRES).execute("create schema public");
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of("appDbPassword", PostgresContainerConfig.APP_PASSWORD))
                .load()
                .migrate();

        PGSimpleDataSource app = new PGSimpleDataSource();
        app.setUrl(POSTGRES.getJdbcUrl());
        app.setUser(PostgresContainerConfig.APP_ROLE);
        app.setPassword(PostgresContainerConfig.APP_PASSWORD);
        TransactionAwareDataSourceProxy proxied = new TransactionAwareDataSourceProxy(app);
        dsl = DSL.using(proxied, SQLDialect.POSTGRES);
        tenantContext.tenant = tenant;
        unitOfWork = new SpringUnitOfWork(
                new DataSourceTransactionManager(app),
                new TestScopeHook(tenantContext, new JdbcTemplate(proxied)));
        links = new JooqLinkRepository(dsl, json, unitOfWork);

        connectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        admin = new RabbitAdmin(connectionFactory);
        rabbit = new RabbitTemplate(connectionFactory);
    }

    @Test
    void aCapOfFiveUnderAHundredParallelRequestsCountsExactlyFiveClicks() throws Exception {
        // The tenant has to exist for row-level security to bind, but a link's own row is all the redirect reads.
        insertTenant();
        unitOfWork.transactional(() -> links.insert(cappedLink(5L)));
        RedirectController controller = controller(ingestBufferThatStoresNothing());

        AtomicInteger redirects = new AtomicInteger();
        AtomicInteger gone = new AtomicInteger();
        java.util.Set<Integer> statuses = java.util.Collections.synchronizedSet(new java.util.TreeSet<>());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(100)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        int status = redirect(controller, "spring-sale").getStatus();
                        statuses.add(status);
                        if (status == 302) {
                            redirects.incrementAndGet();
                        } else if (status == 410) {
                            gone.incrementAndGet();
                        }
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }

        assertThat(redirects.get()).as("a cap of 5 must yield exactly 5 redirects; statuses: " + statuses).isEqualTo(5);
        assertThat(gone.get()).isEqualTo(95);
        assertThat(unitOfWork.transactional(() -> links.clicksCounted(cappedLinkId))).isEqualTo(5L);
    }

    @Test
    void twoThousandClicksProduceTwoThousandMessagesOnTheQueue() throws Exception {
        insertTenant();
        unitOfWork.transactional(() -> links.insert(cappedLink(null)));
        String queue = "attribution.ingest";
        admin.declareExchange(new org.springframework.amqp.core.TopicExchange("attribution.ingest", true, false));
        Queue ingestQueue = QueueBuilder.durable(queue).build();
        admin.declareQueue(ingestQueue);
        admin.declareBinding(new org.springframework.amqp.core.Binding(
                queue, org.springframework.amqp.core.Binding.DestinationType.QUEUE, "attribution.ingest", "click", null));
        admin.purgeQueue(queue, false);

        ClickPublisher publisher = new ClickPublisher(rabbit, json, "attribution.ingest", meters);
        IngestBuffer buffer = new IngestBuffer(5_000, 500, meters);
        buffer.start(publisher::publish);
        RedirectController controller = controller(buffer);

        for (int index = 0; index < 2_000; index++) {
            redirect(controller, "spring-sale");
        }
        // Drain what the increments left behind, the way the flusher thread would.
        while (buffer.depth() > 0) {
            buffer.drainOnce(200);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        int messages = 0;
        while (System.nanoTime() < deadline) {
            Long count = admin.getQueueInfo(queue).getMessageCount();
            messages = count == null ? 0 : count.intValue();
            if (messages == 2_000) {
                break;
            }
            Thread.sleep(100);
        }

        assertThat(messages).as("every offered click must reach the queue exactly once").isEqualTo(2_000);
        assertThat(buffer.droppedTotal()).isZero();
    }

    private UUID cappedLinkId;

    private Link cappedLink(Long cap) {
        cappedLinkId = cappedLinkId == null ? UUID.randomUUID() : cappedLinkId;
        return new Link(
                cappedLinkId, tenant, null, Slug.of("spring-sale"), LinkKind.STANDARD, null,
                dev.ngb.attribution.links.domain.link.LinkStatus.ACTIVE, null, cap, null,
                Map.of("source", "newsletter"),
                List.of(new Destination(
                        UUID.randomUUID(), new DestinationUrl("https://example.com/a"), 0, 1, DeviceMatch.any(), true)),
                1, NOW, NOW);
    }

    private RedirectController controller(dev.ngb.attribution.tracking.api.ingest.ClickIngest ingest) {
        return new RedirectController(
                new LinkCache(links, unitOfWork, Duration.ofSeconds(30), 1000),
                new RedirectDecider(links, new java.util.Random(1)),
                new ClickFactory(
                        (Clock) () -> Instant.now(), UUID::randomUUID, tenantContext,
                        new DailyVisitorHash("visitor-secret"), GeoCountryReader.open(null)),
                ingest,
                () -> "aid",
                new dev.ngb.attribution.links.application.link.port.PasswordHasher() {
                    @Override
                    public String hash(String raw) {
                        return "hash:" + raw;
                    }

                    @Override
                    public boolean matches(String raw, String hash) {
                        return ("hash:" + raw).equals(hash);
                    }
                },
                // A generous limit: this test is about the cap and the messages, not the public rate.
                new PublicRateLimiter(10_000_000, Duration.ofMinutes(1)),
                new PublicRateLimiter(10_000_000, Duration.ofMinutes(1)),
                new UnlockAttempts(10_000, Duration.ofMinutes(1)),
                new SuspectTracker(Duration.ofMinutes(1)),
                () -> true,
                new RedirectController.ClickTokenKeys("k1", CLICK_KEYS),
                tenantContext,
                () -> Instant.now());
    }

    private org.springframework.mock.web.MockHttpServletResponse redirect(RedirectController controller, String slug)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/go/" + slug);
        request.setRemoteAddr("203.0.113." + (1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(200)));
        request.addHeader("User-Agent", "Mozilla/5.0 Chrome/120");
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.redirect(slug, null, request, response);
        return response;
    }

    private void insertTenant() {
        dsl.execute(
                "insert into tenants (id, code, name, status, created_at) values (?, ?, ?, 'ACTIVE', now())"
                        + " on conflict (id) do nothing",
                tenant.value(), "t-" + tenant.value().toString().substring(0, 8), "Tenant");
    }

    private dev.ngb.attribution.tracking.api.ingest.ClickIngest ingestBufferThatStoresNothing() {
        return event -> true;
    }

    private static class FixedTenantContext implements TenantContext {

        private TenantId tenant;

        @Override
        public RequestScope current() {
            return RequestScope.tenant(tenant);
        }

        @Override
        public <T> T callAs(RequestScope scope, Supplier<T> work) {
            return work.get();
        }
    }

    private record TestScopeHook(TenantContext tenantContext, JdbcTemplate jdbc) implements TransactionScopeHook {

        @Override
        public RequestScope currentScope() {
            return tenantContext.current();
        }

        @Override
        public void onNewTransaction(RequestScope scope) {
            jdbc.queryForObject(
                    "select set_config('app.tenant_id', ?, true)", String.class, scope.dataTenantId().toString());
        }
    }
}
