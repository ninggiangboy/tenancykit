package dev.ngb.attribution.links.adapter.link.web;

import dev.ngb.attribution.links.adapter.link.qr.ZxingQrRenderer;
import dev.ngb.attribution.links.application.link.command.ChangeLinkStateUseCase;
import dev.ngb.attribution.links.application.link.command.CreateBulkLinksUseCase;
import dev.ngb.attribution.links.application.link.command.CreateLinkUseCase;
import dev.ngb.attribution.links.application.link.command.UpdateLinkUseCase;
import dev.ngb.attribution.links.application.link.port.BulkRequestStore;
import dev.ngb.attribution.links.application.link.query.LinkListing;
import dev.ngb.attribution.links.application.link.query.LinkQueries;
import dev.ngb.attribution.links.application.link.query.LinkStatsQueries;
import dev.ngb.attribution.links.application.link.query.LinkStatsView;
import dev.ngb.attribution.links.application.link.query.LinkView;
import dev.ngb.attribution.links.domain.link.Link;
import dev.ngb.attribution.links.application.link.service.SuiteHosts;
import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tenant's tracked links: create, edit under {@code If-Match}, pause, resume, archive, the QR code and the statistics.
 *
 * <p>A link's public URL is built from the replica's tenant domains, so a link is shareable the moment it is created. The
 * password is accepted on write and never returned; a destination that points at the suite or a private address is
 * refused at save, because that check is the only guard against a link becoming a phishing relay.
 */
@RestController
@RequestMapping("/api/v1/links")
class LinkController {

    private static final String PNG = "image/png";
    private static final String SVG = "image/svg+xml";

    private final CreateLinkUseCase create;
    private final UpdateLinkUseCase update;
    private final ChangeLinkStateUseCase changeState;
    private final CreateBulkLinksUseCase bulk;
    private final LinkQueries queries;
    private final LinkStatsQueries stats;
    private final ZxingQrRenderer qr;
    private final SuiteHosts hosts;

    LinkController(
            CreateLinkUseCase create,
            UpdateLinkUseCase update,
            ChangeLinkStateUseCase changeState,
            CreateBulkLinksUseCase bulk,
            LinkQueries queries,
            LinkStatsQueries stats,
            ZxingQrRenderer qr,
            SuiteHosts hosts) {
        this.create = create;
        this.update = update;
        this.changeState = changeState;
        this.bulk = bulk;
        this.queries = queries;
        this.stats = stats;
        this.qr = qr;
        this.hosts = hosts;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link LinkListing}. */
    @GetMapping
    @Listing(LinkListing.class)
    @RequiresPermission(AttributionPermissions.LINK_READ)
    Page<LinkResponse> list(ListQuery query) {
        return queries.list(query).map(view -> LinkResponse.of(view, publicUrl(view.slug())));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(AttributionPermissions.LINK_MANAGE)
    LinkResponse create(@RequestBody LinkRequest request) {
        Link link = create.execute(new CreateLinkUseCase.Command(
                request.campaignId(),
                request.slug(),
                request.externalRef(),
                request.expiresAt(),
                request.maxClicks(),
                request.password(),
                request.utm(),
                destinations(request.destinations())));
        return LinkResponse.of(link, publicUrl(link.slug().value()));
    }

    @PostMapping("/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(AttributionPermissions.LINK_MANAGE)
    List<BulkRequestStore.PersonalLink> bulk(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody BulkRequest request) {
        return bulk.execute(new CreateBulkLinksUseCase.Command(
                        request.campaignId(),
                        request.destination(),
                        request.utm(),
                        request.expiresAt(),
                        request.rows() == null
                                ? List.of()
                                : request.rows().stream()
                                        .map(row -> new CreateBulkLinksUseCase.Row(row.externalRef()))
                                        .toList(),
                        idempotencyKey))
                .links();
    }

    @GetMapping("/{id}")
    @RequiresPermission(AttributionPermissions.LINK_READ)
    ResponseEntity<LinkResponse> get(@PathVariable UUID id) {
        LinkView view = queries.get(id);
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(view.version()))
                .body(LinkResponse.of(view, publicUrl(view.slug())));
    }

    @PatchMapping("/{id}")
    @ConditionalUpdate
    @RequiresPermission(AttributionPermissions.LINK_MANAGE)
    ResponseEntity<LinkResponse> update(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody LinkRequest request) {
        Link link = update.execute(
                id,
                new UpdateLinkUseCase.Command(
                        request.campaignId(),
                        request.externalRef(),
                        request.expiresAt(),
                        request.maxClicks(),
                        request.password(),
                        request.utm(),
                        destinations(request.destinations())),
                ExpectedVersion.fromIfMatch(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(link.version()))
                .body(LinkResponse.of(link, publicUrl(link.slug().value())));
    }

    @PostMapping("/{id}/pause")
    @RequiresPermission(AttributionPermissions.LINK_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void pause(@PathVariable UUID id) {
        changeState.execute(id, ChangeLinkStateUseCase.Action.PAUSE);
    }

    @PostMapping("/{id}/resume")
    @RequiresPermission(AttributionPermissions.LINK_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resume(@PathVariable UUID id) {
        changeState.execute(id, ChangeLinkStateUseCase.Action.RESUME);
    }

    @PostMapping("/{id}/archive")
    @RequiresPermission(AttributionPermissions.LINK_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@PathVariable UUID id) {
        changeState.execute(id, ChangeLinkStateUseCase.Action.ARCHIVE);
    }

    @GetMapping("/{id}/qr")
    @RequiresPermission(AttributionPermissions.LINK_READ)
    ResponseEntity<byte[]> qr(
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "png") String format,
            @RequestParam(required = false, defaultValue = "256") int size) {
        LinkView view = queries.get(id);
        String url = publicUrl(view.slug()) + "?e=qr";
        if ("svg".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf(SVG))
                    .body(qr.svg(url, size).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok().contentType(MediaType.valueOf(PNG)).body(qr.png(url, size));
    }

    @GetMapping("/{id}/stats")
    @RequiresPermission(AttributionPermissions.LINK_READ)
    LinkStatsView stats(@PathVariable UUID id) {
        return stats.stats(id);
    }

    private String publicUrl(String slug) {
        return hosts.publicBaseUrl() + "/go/" + slug;
    }

    private static List<CreateLinkUseCase.DestinationInput> destinations(List<DestinationRequest> destinations) {
        if (destinations == null) {
            return List.of();
        }
        return destinations.stream()
                .map(destination -> new CreateLinkUseCase.DestinationInput(
                        destination.url(),
                        destination.weight() == null ? 1 : destination.weight(),
                        destination.devices(),
                        destination.countries(),
                        destination.fallback()))
                .toList();
    }

    record DestinationRequest(String url, Integer weight, List<String> devices, List<String> countries, boolean fallback) {}

    record LinkRequest(
            UUID campaignId,
            String slug,
            String externalRef,
            Instant expiresAt,
            Long maxClicks,
            String password,
            Map<String, String> utm,
            List<DestinationRequest> destinations) {}

    record BulkLinkRow(String externalRef) {}

    record BulkRequest(
            UUID campaignId,
            String destination,
            Map<String, String> utm,
            Instant expiresAt,
            List<BulkLinkRow> rows) {}

    record LinkResponse(
            UUID id,
            UUID campaignId,
            String slug,
            String kind,
            String externalRef,
            String status,
            String url,
            Instant expiresAt,
            Long maxClicks,
            boolean hasPassword,
            Map<String, String> utm,
            List<LinkView.DestinationView> destinations,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        static LinkResponse of(Link link, String url) {
            return new LinkResponse(
                    link.id(), link.campaignId(), link.slug().value(), link.kind().name(), link.externalRef(),
                    link.status().name(), url, link.expiresAt(), link.maxClicks(), link.hasPassword(), link.utm(),
                    link.destinations().stream()
                            .map(destination -> new LinkView.DestinationView(
                                    destination.id(),
                                    destination.url().value(),
                                    destination.position(),
                                    destination.weight(),
                                    destination.fallback(),
                                    destination.match().devices().stream().map(deviceClass -> deviceClass.name()).toList(),
                                    destination.match().countries()))
                            .toList(),
                    link.version(), link.createdAt(), link.updatedAt());
        }

        static LinkResponse of(LinkView view, String url) {
            return new LinkResponse(
                    view.id(), view.campaignId(), view.slug(), view.kind(), view.externalRef(), view.status(), url,
                    view.expiresAt(), view.maxClicks(), view.hasPassword(), view.utm(), view.destinations(),
                    view.version(), view.createdAt(), view.updatedAt());
        }
    }
}
