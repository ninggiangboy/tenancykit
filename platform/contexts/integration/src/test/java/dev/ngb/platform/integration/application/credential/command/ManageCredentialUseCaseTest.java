package dev.ngb.platform.integration.application.credential.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.id.TenantSystemId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.RequestScope;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.platform.integration.application.credential.port.CredentialRepository;
import dev.ngb.platform.integration.application.credential.port.TenantSystemScopes;
import dev.ngb.platform.integration.application.tenantsystem.port.TenantSystemRepository;
import dev.ngb.platform.integration.domain.credential.ApiCredential;
import dev.ngb.platform.integration.domain.credential.ApiCredentialId;
import dev.ngb.platform.integration.domain.credential.CredentialStatus;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystem;
import dev.ngb.platform.integration.domain.tenantsystem.TenantSystemPurpose;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Rotation and grantability against in-memory ports: a rotation leaves the outgoing credential alive only for the
 * overlap, a second rotation inside that window is refused, and a code that is not grantable never reaches a
 * credential.
 */
class ManageCredentialUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final TenantSystemId SYSTEM = new TenantSystemId(UUID.randomUUID());
    private static final TenantSystemId WORKFLOW_SYSTEM = new TenantSystemId(UUID.randomUUID());
    private static final Audit CALLER = new Audit("user/admin", "req-1");

    private final Map<String, ApiCredential> byKeyId = new LinkedHashMap<>();
    private final List<AuditEntry> audited = new ArrayList<>();
    private final TenantSystem system = TenantSystem.create(SYSTEM, TENANT, "svc", TenantSystemPurpose.GENERIC, NOW);
    private final TenantSystem workflowSystem =
            TenantSystem.create(WORKFLOW_SYSTEM, TENANT, "flow", TenantSystemPurpose.WORKFLOW, NOW);
    private Instant now = NOW;

    private final UnitOfWork passThrough = new UnitOfWork() {
        @Override
        public <T> T transactional(Supplier<T> work) {
            return work.get();
        }
    };

    private final CredentialRepository credentials = new CredentialRepository() {
        @Override
        public void add(ApiCredential credential) {
            byKeyId.put(credential.keyId(), credential);
        }

        @Override
        public void update(ApiCredential credential) {
            byKeyId.put(credential.keyId(), credential);
        }

        @Override
        public Optional<ApiCredential> findById(ApiCredentialId id) {
            return byKeyId.values().stream().filter(c -> c.id().equals(id)).findFirst();
        }

        @Override
        public List<ApiCredential> lockByTenantSystem(TenantSystemId tenantSystemId) {
            return byKeyId.values().stream().filter(c -> c.tenantSystemId().equals(tenantSystemId)).toList();
        }

        @Override
        public Optional<ApiCredential> findByKeyId(String keyId) {
            return Optional.ofNullable(byKeyId.get(keyId));
        }
    };

    private final TenantSystemRepository systems = new TenantSystemRepository() {
        @Override
        public void add(TenantSystem added) {}

        @Override
        public void update(TenantSystem updated) {}

        @Override
        public Optional<TenantSystem> findById(TenantSystemId id) {
            if (id.equals(SYSTEM)) {
                return Optional.of(system);
            }
            return id.equals(WORKFLOW_SYSTEM) ? Optional.of(workflowSystem) : Optional.empty();
        }

        @Override
        public Optional<TenantSystem> lockById(TenantSystemId id) {
            return findById(id);
        }
    };

    private final TenantSystemScopes grantable = code -> Set.of(
                    "billing:invoice:read", "billing:usage:write", "billing:refund:create", "workflow:definition:read",
                    "platform:replica:read")
            .contains(code);

    private final TenantContext tenantContext = new TenantContext() {
        @Override
        public RequestScope current() {
            return RequestScope.tenant(TENANT);
        }

        @Override
        public <T> T callAs(RequestScope scope, Supplier<T> work) {
            return work.get();
        }
    };

    private final ManageCredentialUseCase useCase = new ManageCredentialUseCase(
            passThrough,
            credentials,
            systems,
            grantable,
            audited::add,
            tenantContext,
            new IdGenerator() {
                @Override
                public UUID newId() {
                    return UUID.randomUUID();
                }
            },
            new Clock() {
                @Override
                public Instant now() {
                    return now;
                }
            },
            new SecureRandom());

    private ManageCredentialUseCase.Issued create(String... scopes) {
        return useCase.create(CALLER, SYSTEM, "svc", Set.of(scopes));
    }

    @Test
    void aCreatedCredentialIsActiveAndAuditedOnce() {
        ManageCredentialUseCase.Issued issued = create("billing:invoice:read");

        assertThat(issued.credential().isUsable(NOW)).isTrue();
        assertThat(issued.plaintextKey()).startsWith("cbk_");
        assertThat(byKeyId).hasSize(1);
        assertThat(audited).extracting(AuditEntry::action).containsExactly("credential.created");
    }

    @Test
    void aCodeThatCannotBeGrantedIsRefusedBeforeAnythingIsStored() {
        assertThatThrownBy(() -> create("platform:tenant:create"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be granted");

        assertThat(byKeyId).isEmpty();
        assertThat(audited).isEmpty();
    }

    @Test
    void aRotationLeavesBothCredentialsUsableOnlyDuringTheOverlap() {
        create("billing:invoice:read");

        ManageCredentialUseCase.Issued rotated = useCase.rotate(CALLER, SYSTEM, Duration.ofHours(1));

        ApiCredential outgoing = byKeyId.get(keyIdOtherThan(rotated));
        assertThat(byKeyId).hasSize(2);
        assertThat(outgoing.isUsable(NOW)).isTrue();
        assertThat(outgoing.isUsable(NOW.plus(Duration.ofHours(1)))).as("the overlap is bounded").isFalse();
        assertThat(rotated.credential().isUsable(NOW.plus(Duration.ofHours(1)))).isTrue();
        assertThat(rotated.credential().scopes()).isEqualTo(outgoing.scopes());
        assertThat(audited).extracting(AuditEntry::action).containsExactly("credential.created", "credential.rotated");
    }

    @Test
    void aSecondRotationInsideTheOverlapWindowIsRefused() {
        create("billing:invoice:read");
        useCase.rotate(CALLER, SYSTEM, Duration.ofHours(1));

        assertThatThrownBy(() -> useCase.rotate(CALLER, SYSTEM, Duration.ofHours(1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("overlap window");
        assertThat(byKeyId).hasSize(2);
    }

    @Test
    void aRotationWithoutALiveCredentialIsRefused() {
        assertThatThrownBy(() -> useCase.rotate(CALLER, SYSTEM, Duration.ofHours(1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no active credential");
    }

    @Test
    void theOverlapHasABoundedRange() {
        create("billing:invoice:read");

        assertThatThrownBy(() -> useCase.rotate(CALLER, SYSTEM, Duration.ofSeconds(59)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.rotate(CALLER, SYSTEM, Duration.ofDays(8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(byKeyId).hasSize(1);
    }

    @Test
    void revokingReportsAnotherTenantSystemsCredentialAsAbsent() {
        create("billing:invoice:read");
        ApiCredentialId id = byKeyId.values().iterator().next().id();

        assertThatThrownBy(() -> useCase.revoke(CALLER, new TenantSystemId(UUID.randomUUID()), id))
                .isInstanceOf(NotFoundException.class);

        assertThat(useCase.revoke(CALLER, SYSTEM, id).status()).isEqualTo(CredentialStatus.REVOKED);
        assertThat(audited).extracting(AuditEntry::action).containsExactly("credential.created", "credential.revoked");
    }

    @Test
    void aDisabledTenantSystemCannotBeIssuedACredential() {
        system.disable();

        assertThatThrownBy(() -> create("billing:invoice:read"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void anUnknownTenantSystemIsNotFound() {
        TenantSystemId missing = new TenantSystemId(UUID.randomUUID());

        assertThatThrownBy(() -> useCase.create(CALLER, missing, "svc", Set.of("billing:invoice:read")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("tenant system");
    }

    @Test
    void aWorkflowSystemCannotCarryAPlatformCode() {
        assertThatThrownBy(() -> useCase.create(CALLER, WORKFLOW_SYSTEM, "flow", Set.of("platform:replica:read")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflow tenant system");

        assertThat(byKeyId).isEmpty();
    }

    @Test
    void aWorkflowSystemMayCarryAppAndWorkflowCodes() {
        ManageCredentialUseCase.Issued issued = useCase.create(
                CALLER, WORKFLOW_SYSTEM, "flow", Set.of("billing:refund:create", "workflow:definition:read"));

        assertThat(issued.credential().scopes())
                .containsExactlyInAnyOrder("billing:refund:create", "workflow:definition:read");
    }

    @Test
    void aGenericSystemMayStillCarryAWorkflowCode() {
        ManageCredentialUseCase.Issued issued = create("workflow:definition:read");

        assertThat(issued.credential().scopes()).containsExactly("workflow:definition:read");
    }

    private String keyIdOtherThan(ManageCredentialUseCase.Issued issued) {
        return byKeyId.keySet().stream()
                .filter(keyId -> !byKeyId.get(keyId).id().equals(issued.credential().id()))
                .findFirst()
                .orElseThrow();
    }
}
