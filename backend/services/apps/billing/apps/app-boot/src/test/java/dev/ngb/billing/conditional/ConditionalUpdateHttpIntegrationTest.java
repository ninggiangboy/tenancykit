package dev.ngb.billing.conditional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.billing.Application;
import dev.ngb.billing.customer.application.customer.command.ManageCustomerUseCase;
import dev.ngb.billing.customer.application.customer.query.CustomerQueries;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.support.HttpWorld;
import dev.ngb.billing.support.HttpWorld.Account;
import dev.ngb.billing.support.OwnerDatabase;
import dev.ngb.billing.support.PostgresContainerConfig;
import dev.ngb.billing.support.ReplicaFixtures;
import dev.ngb.billing.support.ScopeTestSupport;
import dev.ngb.billing.support.TestTokensConfig;
import dev.ngb.billing.support.UncommittedRace;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Customers and webhook endpoints answer an ETag and honour an optional If-Match: absent is last-write-wins, current
 * is applied, stale is 412 with the current tag and no audit row, {@code *} passes, a weak tag never matches, and a
 * malformed header is 400. A Tenant System that cannot see the customer still gets 404, never a 412.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, TestTokensConfig.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConditionalUpdateHttpIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ScopeTestSupport scopes;
    @Autowired ReplicaFixtures replica;
    @Autowired OwnerDatabase owner;
    @Autowired UnitOfWork unitOfWork;
    @Autowired ManageCustomerUseCase manage;
    @Autowired CustomerQueries customerQueries;

    private static final Audit AUDIT = Audit.of("user/tester", "req-conditional");

    HttpWorld world;
    Account acme;
    Account globex;
    String acmeKey;
    String acmeSystemId;
    String globexKey;

    @BeforeAll
    void createWorld() {
        world = new HttpWorld(mvc, replica, scopes);
        acme = world.newTenant();
        globex = world.newTenant();
        HttpWorld.System system = world.newSystem(
                acme, "billing:customer:read", "billing:customer:write", "billing:webhook:read", "billing:webhook:manage");
        acmeKey = system.token();
        acmeSystemId = system.id();
        globexKey = world.newSystem(globex, "billing:customer:read", "billing:customer:write").token();
    }

    @Test
    void customerUpdateFollowsTheConditionalMatrix() throws Exception {
        String id = newCustomer();
        assertCustomerMatrix("/system/v1/customers/" + id, HttpMethod.PUT,
                HttpWorld.Map2.of("displayName", "Renamed", "email", "billing@acme.test"));
    }

    @Test
    void customerDeactivateFollowsTheConditionalMatrix() throws Exception {
        assertCustomerMatrix("/system/v1/customers/" + newCustomer() + "/deactivate", HttpMethod.POST, null);
    }

    @Test
    void theCustomerGetAnswersAnEtag() throws Exception {
        String id = newCustomer();

        MvcResult got = call(acme.host(), acmeKey, HttpMethod.GET, "/system/v1/customers/" + id, null, Map.of());
        assertThat(got.getResponse().getStatus()).isEqualTo(200);
        assertThat(got.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    }

    @Test
    void aCustomerAnotherTenantSystemCannotSeeIsNotFoundNot412() throws Exception {
        String id = newCustomer();
        String otherSystem = world.newSystem(acme, "billing:customer:read", "billing:customer:write").token();

        MvcResult unmapped = call(acme.host(), otherSystem, HttpMethod.PUT, "/system/v1/customers/" + id,
                HttpWorld.Map2.of("displayName", "Hijack"), Map.of("If-Match", "\"1\""));
        assertThat(unmapped.getResponse().getStatus())
                .as("visibility is checked first, so the version of an unmapped customer does not leak")
                .isEqualTo(404);

        MvcResult crossTenant = call(globex.host(), globexKey, HttpMethod.PUT, "/system/v1/customers/" + id,
                HttpWorld.Map2.of("displayName", "Hijack"), Map.of("If-Match", "\"1\""));
        assertThat(crossTenant.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aLostUpdateOnOneCustomerIsRejected() throws Exception {
        String id = newCustomer();
        TenantId tenant = new TenantId(UUID.fromString(acme.tenantId()));
        CustomerId customerId = CustomerId.of(id);
        ExpectedVersion pinned = ExpectedVersion.fromIfMatch("\"1\"");

        // Both writers carry the same tag. The first holds the lock and commits; the second then sees a newer version.
        assertThatThrownBy(() -> UncommittedRace.run(unitOfWork, scopes, owner, tenant, "%for no key update%",
                () -> manage.update(AUDIT, customerId, "First writer", null, pinned),
                () -> scopes.inTenant(tenant, () -> manage.update(AUDIT, customerId, "Second writer", null, pinned))))
                .as("the second writer's tag is stale once the first has committed")
                .hasRootCauseInstanceOf(PreconditionFailedException.class);
        assertThat(scopes.inTenant(tenant, () -> customerQueries.get(customerId)).displayName())
                .as("the rejected writer changed nothing")
                .isEqualTo("First writer");
    }

    @Test
    void webhookEndpointPatchAndGetCarryTheTag() throws Exception {
        String id = newEndpoint();
        String path = "/system/v1/webhook-endpoints/" + id;
        Map<String, Object> body = HttpWorld.Map2.of("description", "Renamed");

        MvcResult got = call(acme.host(), acmeKey, HttpMethod.GET, path, null, Map.of());
        assertThat(got.getResponse().getHeader("ETag")).isEqualTo("\"1\"");

        MvcResult stale = call(acme.host(), acmeKey, HttpMethod.PATCH, path, body, Map.of("If-Match", "\"9\""));
        assertThat(stale.getResponse().getStatus()).isEqualTo(412);
        assertThat(stale.getResponse().getHeader("ETag")).isEqualTo("\"1\"");

        MvcResult weak = call(acme.host(), acmeKey, HttpMethod.PATCH, path, body, Map.of("If-Match", "W/\"1\""));
        assertThat(weak.getResponse().getStatus()).isEqualTo(412);

        MvcResult malformed = call(acme.host(), acmeKey, HttpMethod.PATCH, path, body, Map.of("If-Match", "1"));
        assertThat(malformed.getResponse().getStatus()).isEqualTo(400);

        MvcResult current = call(acme.host(), acmeKey, HttpMethod.PATCH, path, body, Map.of("If-Match", "\"1\""));
        assertThat(current.getResponse().getStatus()).isEqualTo(200);
        assertThat(current.getResponse().getHeader("ETag")).isEqualTo("\"2\"");

        MvcResult star = call(acme.host(), acmeKey, HttpMethod.PATCH, path, body, Map.of("If-Match", "*"));
        assertThat(star.getResponse().getStatus()).isEqualTo(200);

        MvcResult absent = call(acme.host(), acmeKey, HttpMethod.PATCH, path, body, Map.of());
        assertThat(absent.getResponse().getStatus()).isEqualTo(200);
        assertThat(absent.getResponse().getHeader("ETag")).isNotNull();
    }

    /** The customer matrix; a fresh customer starts at version 1. A rejected write leaves no audit row. */
    private void assertCustomerMatrix(String path, HttpMethod method, Object body) throws Exception {
        long auditsBefore = owner.count("select count(*) from audit_log where tenant_id = ?", UUID.fromString(acme.tenantId()));

        MvcResult stale = call(acme.host(), acmeKey, method, path, body, Map.of("If-Match", "\"999\""));
        assertThat(stale.getResponse().getStatus()).isEqualTo(412);
        assertThat(stale.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
        assertThat(owner.count("select count(*) from audit_log where tenant_id = ?", UUID.fromString(acme.tenantId())))
                .as("a rejected write audits nothing")
                .isEqualTo(auditsBefore);

        MvcResult weak = call(acme.host(), acmeKey, method, path, body, Map.of("If-Match", "W/\"1\""));
        assertThat(weak.getResponse().getStatus()).isEqualTo(412);

        MvcResult malformed = call(acme.host(), acmeKey, method, path, body, Map.of("If-Match", "1"));
        assertThat(malformed.getResponse().getStatus()).isEqualTo(400);

        MvcResult current = call(acme.host(), acmeKey, method, path, body, Map.of("If-Match", "\"1\""));
        assertThat(current.getResponse().getStatus()).isEqualTo(200);
        assertThat(current.getResponse().getHeader("ETag")).isEqualTo("\"2\"");

        MvcResult star = call(acme.host(), acmeKey, method, path, body, Map.of("If-Match", "*"));
        assertThat(star.getResponse().getStatus()).isEqualTo(200);

        MvcResult absent = call(acme.host(), acmeKey, method, path, body, Map.of());
        assertThat(absent.getResponse().getStatus()).isEqualTo(200);
        assertThat(absent.getResponse().getHeader("ETag")).isNotNull();
    }

    private String newCustomer() {
        MvcResult created = call(acme.host(), acmeKey, HttpMethod.POST, "/system/v1/customers",
                HttpWorld.Map2.of("tenantSystemId", acmeSystemId, "externalUserId", "u-" + UUID.randomUUID(),
                        "displayName", "Acme Ltd", "email", "billing@acme.test"),
                Map.of("Idempotency-Key", "k-" + UUID.randomUUID()));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return world.body(created).get("id").asString();
    }

    private String newEndpoint() {
        MvcResult created = call(acme.host(), acmeKey, HttpMethod.POST, "/system/v1/webhook-endpoints",
                HttpWorld.Map2.of("url", "https://hooks.example.test/x", "eventTypes", List.of("*")), Map.of());
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        return world.body(created).get("endpoint").get("id").asString();
    }

    private MvcResult call(String host, String token, HttpMethod method, String path, Object body, Map<String, String> headers) {
        return world.call(method, host, token, path, body, headers);
    }
}
