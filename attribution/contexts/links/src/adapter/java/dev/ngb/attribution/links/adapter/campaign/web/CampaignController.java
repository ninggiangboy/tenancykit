package dev.ngb.attribution.links.adapter.campaign.web;

import dev.ngb.attribution.links.application.campaign.command.ArchiveCampaignUseCase;
import dev.ngb.attribution.links.application.campaign.command.CreateCampaignUseCase;
import dev.ngb.attribution.links.application.campaign.command.UpdateCampaignUseCase;
import dev.ngb.attribution.links.application.campaign.query.CampaignQueries;
import dev.ngb.attribution.links.application.campaign.query.CampaignView;
import dev.ngb.attribution.links.domain.campaign.AttributionModel;
import dev.ngb.attribution.links.domain.campaign.Campaign;
import dev.ngb.attribution.shared.AttributionPermissions;
import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tenant's campaigns. An update is guarded by {@code If-Match}, so a concurrent edit answers 412 with the current tag
 * rather than silently overwriting the other one; a change of the model or the window is what starts a recompute.
 */
@RestController
@RequestMapping("/api/v1/campaigns")
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

    @GetMapping
    @RequiresPermission(AttributionPermissions.CAMPAIGN_READ)
    List<CampaignResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID after,
            @RequestParam(required = false) Integer limit) {
        return queries.list(status, after, limit).stream().map(CampaignResponse::of).toList();
    }

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

    @GetMapping("/{id}")
    @RequiresPermission(AttributionPermissions.CAMPAIGN_READ)
    ResponseEntity<CampaignResponse> get(@PathVariable UUID id) {
        CampaignView view = queries.get(id);
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(view.version())).body(CampaignResponse.of(view));
    }

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
