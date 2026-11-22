package dev.ngb.attribution.links.application.link.command;

import dev.ngb.attribution.links.application.link.port.LinkRepository;
import dev.ngb.attribution.links.domain.link.Link;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.error.PreconditionFailedException;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.util.UUID;

/** Pauses, resumes and archives a link. Only an active link redirects, so a pause ends the redirect at once. */
public class ChangeLinkStateUseCase {

    public enum Action {
        PAUSE,
        RESUME,
        ARCHIVE
    }

    private final UnitOfWork unitOfWork;
    private final LinkRepository links;
    private final Clock clock;

    public ChangeLinkStateUseCase(UnitOfWork unitOfWork, LinkRepository links, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.links = links;
        this.clock = clock;
    }

    public Link execute(UUID linkId, Action action) {
        return unitOfWork.transactional(() -> {
            Link current = links.findById(linkId)
                    .orElseThrow(() -> new NotFoundException("No link exists here."));
            Link next = switch (action) {
                case PAUSE -> current.pause(clock.now());
                case RESUME -> current.resume(clock.now());
                case ARCHIVE -> current.archive(clock.now());
            };
            if (!links.update(next, current.version())) {
                throw new PreconditionFailedException(null);
            }
            return next;
        });
    }
}
