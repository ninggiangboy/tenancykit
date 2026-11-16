package dev.ngb.attribution.links.application.link.command;

import dev.ngb.attribution.links.application.link.port.BulkRequestStore;
import dev.ngb.attribution.links.application.link.port.ClickParamProvider;
import dev.ngb.attribution.links.application.link.port.LinkRepository;
import dev.ngb.attribution.links.application.link.service.RequestFingerprint;
import dev.ngb.attribution.links.application.link.service.SuiteHosts;
import dev.ngb.attribution.links.domain.link.Destination;
import dev.ngb.attribution.links.domain.link.DestinationUrl;
import dev.ngb.attribution.links.domain.link.DeviceMatch;
import dev.ngb.attribution.links.domain.link.Link;
import dev.ngb.attribution.links.domain.link.LinkKind;
import dev.ngb.attribution.links.domain.link.Slug;
import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.error.IdempotencyConflictException;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Mints one personal link per row against a shared destination and UTM, in a single batch.
 *
 * <p>The whole batch is one transaction: either every row produces a link or none does, so a caller never has to
 * reconcile a half-written batch. A replay of the same {@code Idempotency-Key} with the same body returns the links
 * already created; the same key with a different body is refused rather than minting a second batch. The request hash
 * is over the tenant, the shared fields in a stable order and the rows in the order they arrived.
 */
public class CreateBulkLinksUseCase {

    public static final int MAX_ROWS = 1000;

    private static final String SCOPE = "links.bulk";
    private static final int MAX_SLUG_ATTEMPTS = 5;

    /** One row of a batch: the recipient's own reference, which the resulting link carries back. */
    public record Row(String externalRef) {}

    public record Command(
            UUID campaignId,
            String destinationUrl,
            Map<String, String> utm,
            Instant expiresAt,
            List<Row> rows,
            String idempotencyKey) {}

    private final UnitOfWork unitOfWork;
    private final TenantContext tenantContext;
    private final LinkRepository links;
    private final BulkRequestStore requests;
    private final SuiteHosts hosts;
    private final ClickParamProvider clickParam;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateBulkLinksUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            LinkRepository links,
            BulkRequestStore requests,
            SuiteHosts hosts,
            ClickParamProvider clickParam,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.tenantContext = tenantContext;
        this.links = links;
        this.requests = requests;
        this.hosts = hosts;
        this.clickParam = clickParam;
        this.ids = ids;
        this.clock = clock;
    }

    public BulkRequestStore.BulkResult execute(Command command) {
        TenantId tenantId = tenantContext.requireTenant();
        return unitOfWork.transactional(() -> {
            String key = command.idempotencyKey();
            if (key == null || key.isBlank()) {
                throw new BadRequestException("A bulk request needs an idempotency key.");
            }
            List<Row> rows = command.rows() == null ? List.of() : command.rows();
            if (rows.size() > MAX_ROWS) {
                throw new BadRequestException("A bulk request carries at most " + MAX_ROWS + " links.");
            }
            for (Row row : rows) {
                String reference = row == null ? null : row.externalRef();
                if (reference == null || reference.isBlank()
                        || reference.strip().length() > Link.MAX_EXTERNAL_REF) {
                    throw new BadRequestException(
                            "An external reference is 1 to " + Link.MAX_EXTERNAL_REF + " characters.");
                }
            }
            String hash = fingerprint(tenantId, command, rows);
            Optional<BulkRequestStore.BulkResult> stored = requests.find(key);
            if (stored.isPresent()) {
                if (!hash.equals(requests.requestHash(key).orElse(null))) {
                    throw new IdempotencyConflictException(SCOPE, key);
                }
                return stored.get();
            }
            DestinationUrl destination = DestinationUrl.forSuite(command.destinationUrl(), hosts.forbidden());
            String parameter = clickParam.current();
            if (parameter != null && !parameter.isBlank() && destination.queryHas(parameter)) {
                throw new BadRequestException("A destination URL already carries the click parameter.");
            }
            Instant now = clock.now();
            List<BulkRequestStore.PersonalLink> created = new ArrayList<>(rows.size());
            for (Row row : rows) {
                String slug = generateSlug();
                Destination only = new Destination(ids.newId(), destination, 0, 1, DeviceMatch.any(), true);
                Link link = Link.create(
                        ids.newId(), tenantId, command.campaignId(), Slug.of(slug), LinkKind.PERSONAL,
                        row.externalRef(), command.expiresAt(), null, null, command.utm(),
                        List.of(only), now);
                links.insert(link);
                created.add(new BulkRequestStore.PersonalLink(
                        link.id(), slug, hosts.publicBaseUrl() + "/go/" + slug, row.externalRef()));
            }
            BulkRequestStore.BulkResult result = new BulkRequestStore.BulkResult(List.copyOf(created));
            requests.save(key, hash, result);
            return result;
        });
    }

    private String generateSlug() {
        for (int attempt = 0; attempt < MAX_SLUG_ATTEMPTS; attempt++) {
            String candidate = Slug.generate().value();
            if (!links.slugTaken(candidate)) {
                return candidate;
            }
        }
        throw new ConflictException("A unique link slug could not be generated.");
    }

    private static String fingerprint(TenantId tenantId, Command command, List<Row> rows) {
        List<String> parts = new ArrayList<>();
        parts.add(tenantId.value().toString());
        parts.add(String.valueOf(command.campaignId()));
        parts.add(command.destinationUrl() == null ? "" : command.destinationUrl());
        Map<String, String> utm = command.utm() == null ? Map.of() : new TreeMap<>(command.utm());
        utm.forEach((name, value) -> parts.add(name + "=" + value));
        parts.add(String.valueOf(command.expiresAt()));
        for (Row row : rows) {
            parts.add(row == null || row.externalRef() == null ? "" : row.externalRef());
        }
        return RequestFingerprint.of(parts.toArray(String[]::new));
    }
}
