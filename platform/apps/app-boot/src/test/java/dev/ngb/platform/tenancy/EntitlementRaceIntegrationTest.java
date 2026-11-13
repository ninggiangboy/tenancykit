package dev.ngb.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.support.OwnerDatabase;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.support.ReplicaHarness;
import dev.ngb.platform.support.UncommittedRace;
import dev.ngb.platform.tenancy.application.entitlement.command.SetAppEntitlementUseCase;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import dev.ngb.platform.tenancy.domain.entitlement.AppEntitlement;
import dev.ngb.platform.tenancy.domain.tenant.Tenant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A concurrent first enable and disable on one (tenant, app) must publish versions 1 and 2: the losing insert sees
 * nothing inserted, takes the winner's row under lock and bumps it, so the replica keeps the state of version 2
 * instead of dropping the second event as a duplicate.
 */
@SpringBootTest(classes = Application.class)
@Import(PlatformContainerConfig.class)
class EntitlementRaceIntegrationTest {

    private static final String APP = "billing";

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired CreateTenantUseCase createTenant;
    @Autowired SetAppEntitlementUseCase setApp;
    @Autowired TenantContext tenantContext;
    @Autowired UnitOfWork unitOfWork;
    @Autowired OwnerDatabase owner;

    @Test
    @SuppressWarnings("unchecked")
    void aConcurrentFirstEnableAndDisableGiveVersionsOneAndTwo() throws Exception {
        Tenant tenant = newTenant();

        UncommittedRace.run(
                unitOfWork, tenantContext, RequestScope.platform(), owner, "%app_entitlements%",
                () -> enable(tenant), () -> disable(tenant));

        List<JsonNode> events = events(tenant);
        assertThat(events).as("each committed disposition publishes one event").hasSize(2);
        assertThat(events.stream().map(payload -> payload.get("version").asLong()).toList())
                .as("the losing insert bumps the winner's row instead of writing a duplicate version")
                .containsExactly(1L, 2L);

        JsonNode newest = events.getLast();
        try (ReplicaHarness replica = ReplicaHarness.create()) {
            replica.tenant(Map.of(
                    "id", tenant.id().value().toString(),
                    "code", tenant.code().value(),
                    "name", tenant.name(),
                    "status", "ACTIVE",
                    "created_at", "2026-01-01T00:00:00Z",
                    "version", 1));
            for (JsonNode event : events) {
                replica.apply("entitlement.changed", json.convertValue(event, Map.class));
            }
            assertThat(replica.entitlement(tenant.id().value(), APP))
                    .as("the replica ends with the state of the higher version")
                    .containsEntry("version", newest.get("version").asLong())
                    .containsEntry("enabled", newest.get("enabled").asBoolean());
        }
    }

    private AppEntitlement enable(Tenant tenant) {
        return setApp.execute(new SetAppEntitlementUseCase.Command(tenant.id(), APP, true, "user/tester", "req-enable", ExpectedVersion.any()));
    }

    private AppEntitlement disable(Tenant tenant) {
        return setApp.execute(new SetAppEntitlementUseCase.Command(tenant.id(), APP, false, "user/tester", "req-disable", ExpectedVersion.any()));
    }

    private List<JsonNode> events(Tenant tenant) {
        return owner.jdbc().query(
                "select payload::text from outbox where type = 'entitlement.changed' and tenant_id = ?"
                        + " order by (payload ->> 'version')::bigint",
                (rs, rowNum) -> json.readTree(rs.getString(1)),
                tenant.id().value());
    }

    private Tenant newTenant() {
        String code = "ent" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return onPlatform(() -> createTenant.execute(new CreateTenantUseCase.Command(code, "Entitled " + code, "user/tester", "req-1")));
    }

    private <T> T onPlatform(Supplier<T> work) {
        return tenantContext.callAs(RequestScope.platform(), work);
    }
}
