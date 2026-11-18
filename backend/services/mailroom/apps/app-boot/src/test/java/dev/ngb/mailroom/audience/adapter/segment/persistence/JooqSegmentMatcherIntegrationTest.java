package dev.ngb.mailroom.audience.adapter.segment.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailroom.Application;
import dev.ngb.mailroom.audience.api.audience.ReceiverSummary;
import dev.ngb.mailroom.audience.application.receiver.command.UpsertReceiverUseCase;
import dev.ngb.mailroom.support.PostgresContainerConfig;
import dev.ngb.mailroom.support.RabbitContainerConfig;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The compiled segment against real JSONB rows.
 *
 * <p>The property under test is the one the reviewer found broken: a predicate must not depend on every row holding
 * the same type under the same key. One receiver with {@code age = "unknown"} used to abort the whole query, which
 * meant a preview or a campaign's snapshot failed for the tenant until someone fixed the data by hand.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("api")
class JooqSegmentMatcherIntegrationTest {

    @Autowired JooqSegmentMatcher matcher;
    @Autowired UpsertReceiverUseCase upsert;
    @Autowired TenantContext tenantContext;
    @Autowired UnitOfWork unitOfWork;

    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        var postgres = PostgresContainerConfig.postgres();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> PostgresContainerConfig.APP_ROLE);
        registry.add("spring.datasource.password", () -> PostgresContainerConfig.APP_PASSWORD);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.flyway.placeholders.appDbPassword", () -> PostgresContainerConfig.APP_PASSWORD);
        var rabbit = RabbitContainerConfig.rabbit();
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("suite.replica.rebuild-on-start", () -> "false");
        registry.add("mailroom.jobs.enabled", () -> "false");
        registry.add("mailroom.tracking.keys.k1", () -> "test-tracking-secret");
    }

    @Test
    void aNumericPredicateIgnoresARowWhoseAttributeHasAnotherType() {
        RequestScope scope = RequestScope.tenant(tenant);
        receiver(scope, "adult@example.com", Map.of("age", 30));
        receiver(scope, "minor@example.com", Map.of("age", 12));
        // The row that used to make the whole query fail: same key, a string value.
        receiver(scope, "unknown@example.com", Map.of("age", "unknown"));

        Map<String, Object> filter = Map.of("field", "age", "op", "gte", "value", 18);

        assertThat(scoped(scope, () -> matcher.count(tenant, filter))).isEqualTo(1);
        assertThat(scoped(scope, () -> matcher.slice(tenant, filter, null, 10)))
                .extracting(ReceiverSummary::email)
                .containsExactly("adult@example.com");
    }

    @Test
    void equalityComparesTheJsonbTypeAsWellAsTheText() {
        var scope = RequestScope.tenant(tenant);
        receiver(scope, "numeric@example.com", Map.of("plan", 3));
        receiver(scope, "textual@example.com", Map.of("plan", "3"));

        // The number 3 and the string "3" are different attribute values; JSONB containment says so exactly.
        assertThat(scoped(scope, () -> matcher.count(tenant, Map.of("field", "plan", "op", "eq", "value", 3))))
                .isEqualTo(1);
        assertThat(scoped(scope, () -> matcher.count(tenant, Map.of("field", "plan", "op", "eq", "value", "3"))))
                .isEqualTo(1);
    }

    @Test
    void membershipAndExistenceAnswerAcrossMixedTypes() {
        var scope = RequestScope.tenant(tenant);
        receiver(scope, "one@example.com", Map.of("plan", "pro"));
        receiver(scope, "two@example.com", Map.of("plan", "team"));
        receiver(scope, "three@example.com", Map.of());

        assertThat(scoped(scope, () -> matcher.count(
                        tenant, Map.of("field", "plan", "op", "in", "value", List.of("pro", "team")))))
                .isEqualTo(2);
        assertThat(scoped(scope, () -> matcher.count(
                        tenant, Map.of("field", "plan", "op", "exists", "value", true))))
                .isEqualTo(2);
        assertThat(scoped(scope, () -> matcher.count(
                        tenant, Map.of("field", "plan", "op", "exists", "value", false))))
                .isEqualTo(1);
    }

    @Test
    void aBooleanAttributeComparesWithoutATypeHint() {
        var scope = RequestScope.tenant(tenant);
        receiver(scope, "churned@example.com", Map.of("churned", true));
        receiver(scope, "active@example.com", Map.of("churned", false));

        // A bare boolean bind must reach to_jsonb with a resolvable type; an ambiguity here would fail the query.
        assertThat(scoped(scope, () -> matcher.count(
                        tenant, Map.of("field", "churned", "op", "eq", "value", true))))
                .isEqualTo(1);
        assertThat(scoped(scope, () -> matcher.count(
                        tenant, Map.of("field", "churned", "op", "neq", "value", true))))
                .isEqualTo(1);
    }

    private void receiver(RequestScope scope, String email, Map<String, Object> attributes) {
        scoped(scope, () -> upsert.execute(new UpsertReceiverUseCase.Command(email, null, null, attributes)));
    }

    private <T> T scoped(RequestScope scope, Supplier<T> work) {
        // Row-level security needs the tenant bound by a unit of work, exactly as a preview or a snapshot reaches it.
        return tenantContext.callAs(scope, () -> unitOfWork.transactional(work));
    }
}
