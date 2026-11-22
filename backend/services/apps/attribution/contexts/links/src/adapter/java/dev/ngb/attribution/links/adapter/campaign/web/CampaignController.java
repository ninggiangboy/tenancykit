package dev.ngb.attribution.links.adapter.campaign.web;

import dev.ngb.attribution.links.application.campaign.command.ArchiveCampaignUseCase;
import dev.ngb.attribution.links.application.campaign.command.CreateCampaignUseCase;
import dev.ngb.attribution.links.application.campaign.command.UpdateCampaignUseCase;
import dev.ngb.attribution.links.application.campaign.query.CampaignListing;
import dev.ngb.attribution.links.application.campaign.query.CampaignQueries;
import dev.ngb.attribution.links.application.campaign.query.CampaignView;
import dev.ngb.attribution.links.domain.campaign.AttributionModel;
import dev.ngb.attribution.links.domain.campaign.Campaign;
import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tenant's campaigns. An update is guarded by {@code If-Match}, so a concurrent edit answers 412 with the current tag
 * rather than silently overwriting the other one; a change of the model or the window is what starts a recompute.
 */
@Tag(
        name = "Campaigns",
        description =
                "A tenant's campaigns, their attribution model and window.")
@RestController
@RequestMapping("/api/v1/campaigns")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
class CampaignController {

    private final CreateCampaignUseCase create;
    private final UpdateCampaignUseCase update;
    private final ArchiveCampaignUseCase archive;
    private final CampaignQueries queries;

    CampaignController(
            CreateCampaignUseCase create,
            UpdateCampaignUseCase update,
            ArchiveCampaignUseCase archive,
            CampaignQueries queries) {
        this.create = create;
        this.update = update;
        this.archive = archive;
        this.queries = queries;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link CampaignListing}. */
    @Operation(
            summary = "List campaigns",
            description =
                    "Pages through the tenant's campaigns, with the list-query contract's filtering, sorting and paging. ")
    @GetMapping
    @Listing(CampaignListing.class)
    @RequiresPermission(AttributionPermissions.CAMPAIGN_READ)
    Page<CampaignResponse> list(ListQuery query) {
        return queries.list(query).map(CampaignResponse::of);
    }

    @Operation(
            summary = "Create a campaign",
            description =
                    "Creates a campaign with a name, a channel, an attribution model, a window in days, a currency and "
                    + "optional start and end dates. The `slug` can be omitted; one is derived from the name, and a "
                    + "collision falls back to a generated one. Answers `201`.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(AttributionPermissions.CAMPAIGN_MANAGE)
    CampaignResponse create(@RequestBody CampaignRequest request) {
        return CampaignResponse.of(create.execute(new CreateCampaignUseCase.Command(
                request.name(),
                request.slug(),
                request.channel(),
                model(request.attributionModel()),
                request.windowDays() == null ? 0 : request.windowDays(),
                request.currency(),
                request.startsOn(),
                request.endsOn())));
    }

    @Operation(
            summary = "Get a campaign",
            description =
                    "Returns one campaign; its version is also the response's `ETag`. An unknown id answers `404`. ")
    @GetMapping("/{id}")
    @RequiresPermission(AttributionPermissions.CAMPAIGN_READ)
    ResponseEntity<CampaignResponse> get(@PathVariable UUID id) {
        CampaignView view = queries.get(id);
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(view.version())).body(CampaignResponse.of(view));
    }

    @Operation(
            summary = "Edit a campaign",
            description =
                    "Changes the campaign's properties. A change of the attribution model or the window is stored at once "
                    + "and starts a recompute of its conversions. The currency is fixed once spend or a conversion exists. "
                    + "Pass the current `ETag` in `If-Match`; a stale version is refused with `412`. Requires "
                    + "`attribution:campaign:manage`.")
    @PatchMapping("/{id}")
    @ConditionalUpdate
    @RequiresPermission(AttributionPermissions.CAMPAIGN_MANAGE)
    ResponseEntity<CampaignResponse> update(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody CampaignRequest request) {
        Campaign campaign = update.execute(
                id,
                new UpdateCampaignUseCase.Change(
                        request.name(),
                        request.channel(),
                        model(request.attributionModel()),
                        request.windowDays() == null ? 0 : request.windowDays(),
                        request.startsOn(),
                        request.endsOn()),
                ExpectedVersion.fromIfMatch(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(campaign.version()))
                .body(CampaignResponse.of(campaign));
    }

    @Operation(
            summary = "Archive a campaign",
            description =
                    "Archives the campaign. Archiving is terminal. Answers `204`.")
    @PostMapping("/{id}/archive")
    @RequiresPermission(AttributionPermissions.CAMPAIGN_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@PathVariable UUID id) {
        archive.execute(id);
    }

    private static AttributionModel model(String value) {
        return value == null || value.isBlank() ? null : AttributionModel.valueOf(value.strip().toUpperCase());
    }

    record CampaignRequest(
            String name,
            String slug,
            String channel,
            String attributionModel,
            Integer windowDays,
            String currency,
            LocalDate startsOn,
            LocalDate endsOn) {}

    record CampaignResponse(
            UUID id,
            String name,
            String slug,
            String channel,
            String status,
            String attributionModel,
            int windowDays,
            String currency,
            LocalDate startsOn,
            LocalDate endsOn,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        static CampaignResponse of(Campaign campaign) {
            return new CampaignResponse(
                    campaign.id(), campaign.name(), campaign.slug(), campaign.channel(), campaign.status().name(),
                    campaign.model().name(), campaign.windowDays(), campaign.currency(), campaign.startsOn(),
                    campaign.endsOn(), campaign.version(), campaign.createdAt(), campaign.updatedAt());
        }

        static CampaignResponse of(CampaignView view) {
            return new CampaignResponse(
                    view.id(), view.name(), view.slug(), view.channel(), view.status().name(),
                    view.model().name(), view.windowDays(), view.currency(), view.startsOn(), view.endsOn(),
                    view.version(), view.createdAt(), view.updatedAt());
        }
    }
}
