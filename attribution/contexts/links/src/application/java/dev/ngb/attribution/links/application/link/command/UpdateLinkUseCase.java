package dev.ngb.attribution.links.application.link.command;

import dev.ngb.attribution.links.application.link.port.ClickParamProvider;
import dev.ngb.attribution.links.application.link.port.LinkRepository;
import dev.ngb.attribution.links.application.link.port.PasswordHasher;
import dev.ngb.attribution.links.application.link.service.SuiteHosts;
import dev.ngb.attribution.links.domain.link.Destination;
import dev.ngb.attribution.links.domain.link.Link;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Edits a link's destinations, its limits and its metadata. A blank password keeps the stored hash, so a caller that
 * never knew the password can still edit the link without clearing it.
 */
public class UpdateLinkUseCase {

    public record Command(
            UUID campaignId,
            String externalRef,
            Instant expiresAt,
            Long maxClicks,
            String password,
            Map<String, String> utm,
            List<CreateLinkUseCase.DestinationInput> destinations) {}

    private final UnitOfWork unitOfWork;
    private final LinkRepository links;
    private final SuiteHosts hosts;
    private final ClickParamProvider clickParam;
    private final PasswordHasher passwordHasher;
    private final IdGenerator ids;
    private final Clock clock;

    public UpdateLinkUseCase(
            UnitOfWork unitOfWork,
            LinkRepository links,
            SuiteHosts hosts,
            ClickParamProvider clickParam,
            PasswordHasher passwordHasher,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.links = links;
        this.hosts = hosts;
        this.clickParam = clickParam;
        this.passwordHasher = passwordHasher;
        this.ids = ids;
        this.clock = clock;
    }

    public Link execute(UUID linkId, Command command, ExpectedVersion expected) {
        return unitOfWork.transactional(() -> {
            Link current = links.findById(linkId)
                    .orElseThrow(() -> new NotFoundException("No link exists here."));
            expected.check(current.version());
            List<Destination> destinations =
                    CreateLinkUseCase.destinations(command.destinations(), hosts, clickParam, ids);
            String passwordHash = command.password() == null || command.password().isBlank()
                    ? current.passwordHash()
                    : passwordHasher.hash(command.password());
            Link updated = current.update(
                    command.campaignId(), command.externalRef(), command.expiresAt(), command.maxClicks(),
                    passwordHash, command.utm(), destinations, clock.now());
            if (!links.update(updated, current.version())) {
                throw new PreconditionFailedException(null);
            }
            return updated;
        });
    }
}
