package dev.ngb.attribution.links.application.link.command;

import dev.ngb.attribution.links.application.link.port.ClickParamProvider;
import dev.ngb.attribution.links.application.link.port.LinkRepository;
import dev.ngb.attribution.links.application.link.port.PasswordHasher;
import dev.ngb.attribution.links.application.link.service.SuiteHosts;
import dev.ngb.attribution.links.domain.link.Destination;
import dev.ngb.attribution.links.domain.link.DestinationUrl;
import dev.ngb.attribution.links.domain.link.DeviceMatch;
import dev.ngb.attribution.links.domain.link.Link;
import dev.ngb.attribution.links.domain.link.LinkKind;
import dev.ngb.attribution.links.domain.link.Slug;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.visitor.DeviceClass;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Creates a tracked link. Every destination is validated here, at save time: an unchecked destination is what would
 * turn the suite's domain into a phishing relay, so the checks run before the row exists.
 *
 * <p>The password is accepted on write only; only its hash is stored, and it is never returned.
 */
public class CreateLinkUseCase {

    private static final int MAX_SLUG_ATTEMPTS = 5;

    public record DestinationInput(
            String url, int weight, List<String> devices, List<String> countries, boolean fallback) {}

    public record Command(
            UUID campaignId,
            String slug,
            String externalRef,
            Instant expiresAt,
            Long maxClicks,
            String password,
            Map<String, String> utm,
            List<DestinationInput> destinations) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final LinkRepository links;
    private final SuiteHosts hosts;
    private final ClickParamProvider clickParam;
    private final PasswordHasher passwordHasher;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateLinkUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            LinkRepository links,
            SuiteHosts hosts,
            ClickParamProvider clickParam,
            PasswordHasher passwordHasher,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.links = links;
        this.hosts = hosts;
        this.clickParam = clickParam;
        this.passwordHasher = passwordHasher;
        this.ids = ids;
        this.clock = clock;
    }

    public Link execute(Command command) {
        TenantId tenantId = tenantContext.requireTenant();
        return unitOfWork.transactional(() -> {
            String slug = resolveSlug(command.slug());
            List<Destination> destinations = destinations(command.destinations(), hosts, clickParam, ids);
            String passwordHash = command.password() == null || command.password().isBlank()
                    ? null
                    : passwordHasher.hash(command.password());
            Link link = Link.create(
                    ids.newId(), tenantId, command.campaignId(), Slug.of(slug), LinkKind.STANDARD,
                    command.externalRef(), command.expiresAt(), command.maxClicks(), passwordHash,
                    command.utm(), destinations, clock.now());
            links.insert(link);
            return link;
        });
    }

    private String resolveSlug(String requested) {
        if (requested != null && !requested.isBlank()) {
            String slug = requested.strip();
            if (links.slugTaken(slug)) {
                throw new ConflictException("A link with that slug already exists.");
            }
            return slug;
        }
        for (int attempt = 0; attempt < MAX_SLUG_ATTEMPTS; attempt++) {
            String candidate = Slug.generate().value();
            if (!links.slugTaken(candidate)) {
                return candidate;
            }
        }
        throw new ConflictException("A unique link slug could not be generated.");
    }

    /**
     * Validates and orders the destinations; shared with the edit path so a link cannot be created under one rule
     * and edited under another. Positions follow the caller's order, which keeps a report and an edit stable.
     */
    static List<Destination> destinations(
            List<DestinationInput> inputs, SuiteHosts hosts, ClickParamProvider clickParam, IdGenerator ids) {
        List<DestinationInput> source = inputs == null ? List.of() : inputs;
        String parameter = clickParam.current();
        List<Destination> destinations = new ArrayList<>(source.size());
        int position = 0;
        for (DestinationInput input : source) {
            if (input == null) {
                throw new BadRequestException("A destination is required.");
            }
            DestinationUrl url = DestinationUrl.forSuite(input.url(), hosts.forbidden());
            if (parameter != null && !parameter.isBlank() && url.queryHas(parameter)) {
                throw new BadRequestException("A destination URL already carries the click parameter.");
            }
            destinations.add(new Destination(
                    ids.newId(), url, position++, input.weight(),
                    new DeviceMatch(deviceClasses(input.devices()), input.countries()), input.fallback()));
        }
        return destinations;
    }

    private static List<DeviceClass> deviceClasses(List<String> names) {
        if (names == null) {
            return List.of();
        }
        List<DeviceClass> classes = new ArrayList<>(names.size());
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                classes.add(DeviceClass.valueOf(name.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("A destination names an unknown device class.");
            }
        }
        return classes;
    }
}
