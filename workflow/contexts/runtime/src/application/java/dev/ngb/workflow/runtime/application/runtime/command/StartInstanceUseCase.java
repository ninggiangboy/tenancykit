package dev.ngb.workflow.runtime.application.runtime.command;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.workflow.definitions.api.version.PublishedGraph;
import dev.ngb.workflow.definitions.api.version.VersionReader;
import dev.ngb.workflow.runtime.application.runtime.port.InstanceRepository;
import dev.ngb.workflow.runtime.application.runtime.port.TokenRepository;
import dev.ngb.workflow.runtime.application.runtime.port.WakeUpPublisher;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.token.Token;
import java.time.Instant;

/**
 * Starts one instance at the trigger of a published version and creates its first token.
 *
 * <p>The version id is the only thing the caller names: the tenant comes from the request scope, so a caller can never
 * start another tenant's workflow, and the instance is pinned to the version it started with.
 */
public class StartInstanceUseCase {

    public record Command(java.util.UUID versionId, Object input, String correlationKey, String startedBy) {}

    private final UnitOfWork unitOfWork;
    private final VersionReader versions;
    private final InstanceRepository instances;
    private final TokenRepository tokens;
    private final WakeUpPublisher wakeUp;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public StartInstanceUseCase(
            UnitOfWork unitOfWork,
            VersionReader versions,
            InstanceRepository instances,
            TokenRepository tokens,
            WakeUpPublisher wakeUp,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.versions = versions;
        this.instances = instances;
        this.tokens = tokens;
        this.wakeUp = wakeUp;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Instance execute(Command command) {
        TenantId tenant = tenantContext.requireTenant();
        Instance instance = unitOfWork.transactional(() -> {
            PublishedGraph version = versions
                    .graph(command.versionId())
                    .orElseThrow(() -> new NotFoundException("No published version exists here."));
            Instant now = clock.now();
            Instance started = Instance.start(
                    ids.newId(),
                    tenant,
                    version.definitionId(),
                    version.versionId(),
                    version.version(),
                    command.input(),
                    command.correlationKey(),
                    command.startedBy(),
                    now);
            instances.add(started);
            tokens.add(Token.ready(
                    ids.newId(), tenant, started.id(), version.graph().start(), null, 0, now));
            return started;
        });
        // After the commit: a worker may claim it now, and a lost message leaves it to the sweeper.
        wakeUp.wake(tenant);
        return instance;
    }
}
