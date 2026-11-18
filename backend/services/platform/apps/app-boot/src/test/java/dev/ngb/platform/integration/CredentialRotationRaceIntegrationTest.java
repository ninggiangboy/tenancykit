package dev.ngb.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.audit.Audit;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.Application;
import dev.ngb.platform.integration.application.credential.command.ManageCredentialUseCase;
import dev.ngb.platform.integration.application.tenantsystem.command.ManageTenantSystemUseCase;
import dev.ngb.platform.integration.domain.credential.ApiCredential;
import dev.ngb.platform.integration.domain.credential.ApiCredentialId;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystem;
import dev.ngb.platform.support.OwnerDatabase;
import dev.ngb.platform.support.PlatformContainerConfig;
import dev.ngb.platform.support.UncommittedRace;
import dev.ngb.platform.tenancy.application.tenant.command.CreateTenantUseCase;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The credential set of one Tenant System is read under the Tenant System's lock and written back with a version
 * guard. Two rotations therefore cannot both extend the same credential, and a rotation can never overwrite a
 * revocation that committed while it was deciding.
 */
@SpringBootTest(classes = Application.class)
@Import(PlatformContainerConfig.class)
class CredentialRotationRaceIntegrationTest {

    private static final Audit CALLER = Audit.of("user/admin", "race-1");
    private static final Duration OVERLAP = Duration.ofMinutes(30);

    @Autowired TenantContext tenantContext;
    @Autowired UnitOfWork unitOfWork;
    @Autowired CreateTenantUseCase createTenant;
    @Autowired ManageTenantSystemUseCase systems;
    @Autowired ManageCredentialUseCase credentials;
    @Autowired OwnerDatabase owner;

    @Test
    void twoConcurrentRotationsLeaveTwoLiveCredentials() throws Exception {
        Setup setup = setup();

        UncommittedRace.Outcome<ManageCredentialUseCase.Issued, ManageCredentialUseCase.Issued> race = UncommittedRace.run(
                unitOfWork, tenantContext, RequestScope.tenant(setup.tenantId()), owner, "%for update%",
                () -> rotate(setup.system().id()),
                () -> rotateUnlessARotationIsInItsOverlap(setup.system().id()));

        assertThat(race.first()).as("the first rotation issues a replacement").isNotNull();
        assertThat(race.second()).as("the second rotation is refused while the first is in its overlap").isNull();
        assertThat(rows(setup.system().id())).as("the refused rotation inserted nothing").isEqualTo(2);
        assertThat(live(setup.system().id())).as("the replacement overlaps the credential it replaces").isEqualTo(2);
    }

    @Test
    void aRevocationCommitsBeforeTheRotationThatWouldResurrectIt() throws Exception {
        Setup setup = setup();
        ApiCredentialId original = setup.issued().credential().id();

        UncommittedRace.Outcome<ApiCredential, ManageCredentialUseCase.Issued> race = UncommittedRace.run(
                unitOfWork, tenantContext, RequestScope.tenant(setup.tenantId()), owner, "%for update%",
                () -> revoke(setup.system().id(), original),
                () -> rotateUnlessARotationIsInItsOverlap(setup.system().id()));

        assertThat(race.first()).as("the revocation commits").isNotNull();
        assertThat(race.second()).as("a revoked credential is not a rotation candidate").isNull();
        assertThat(rows(setup.system().id())).as("the refused rotation inserted nothing").isEqualTo(1);
        assertThat(status(original)).isEqualTo("REVOKED");
        assertThat(live(setup.system().id())).isZero();
    }

    @Test
    void aRotationCommitsBeforeTheRevocationOfTheCredentialItReplaced() throws Exception {
        Setup setup = setup();
        ApiCredentialId original = setup.issued().credential().id();

        UncommittedRace.Outcome<ManageCredentialUseCase.Issued, ApiCredential> race = UncommittedRace.run(
                unitOfWork, tenantContext, RequestScope.tenant(setup.tenantId()), owner, "%for update%",
                () -> rotate(setup.system().id()),
                () -> revoke(setup.system().id(), original));

        assertThat(race.first()).isNotNull();
        assertThat(race.second()).as("the revocation is applied to the credential it names").isNotNull();
        assertThat(status(original)).as("the revocation is not overwritten by the rotation's overlap").isEqualTo("REVOKED");
        assertThat(rows(setup.system().id())).isEqualTo(2);
        assertThat(live(setup.system().id())).as("only the replacement is still usable").isEqualTo(1);
    }

    private ManageCredentialUseCase.Issued rotate(TenantSystemId systemId) {
        return credentials.rotate(CALLER, systemId, OVERLAP);
    }

    private ManageCredentialUseCase.Issued rotateUnlessARotationIsInItsOverlap(TenantSystemId systemId) {
        try {
            return rotate(systemId);
        } catch (ConflictException refused) {
            return null;
        }
    }

    private ApiCredential revoke(TenantSystemId systemId, ApiCredentialId id) {
        return credentials.revoke(CALLER, systemId, id);
    }

    private record Setup(TenantId tenantId, TenantSystem system, ManageCredentialUseCase.Issued issued) {}

    private Setup setup() {
        String code = "crc" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        TenantId tenantId = tenantContext.callAs(
                RequestScope.platform(),
                () -> createTenant.execute(new CreateTenantUseCase.Command(code, "Credential race " + code, "tester", "req-1")).id());
        return tenantContext.callAs(RequestScope.tenant(tenantId), () -> {
            TenantSystem system = systems.create(CALLER, "Sample " + UUID.randomUUID());
            ManageCredentialUseCase.Issued issued = credentials.create(CALLER, system.id(), "ci", Set.of("billing:invoice:read"));
            return new Setup(tenantId, system, issued);
        });
    }

    private long rows(TenantSystemId systemId) {
        return owner.count("select count(*) from api_credentials where tenant_system_id = ?", systemId.value());
    }

    private long live(TenantSystemId systemId) {
        return owner.count(
                "select count(*) from api_credentials"
                        + " where tenant_system_id = ? and status = 'ACTIVE' and (expires_at is null or expires_at > now())",
                systemId.value());
    }

    private String status(ApiCredentialId id) {
        return owner.jdbc().queryForObject("select status from api_credentials where id = ?", String.class, id.value());
    }
}
