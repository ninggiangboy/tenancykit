package dev.ngb.mailroom.sending.adapter.campaign.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.mailroom.sending.application.campaign.command.ChangeCampaignStateUseCase;
import dev.ngb.mailroom.sending.application.campaign.command.CreateCampaignUseCase;
import dev.ngb.mailroom.sending.application.campaign.command.ScheduleCampaignUseCase;
import dev.ngb.mailroom.sending.application.campaign.command.StartCampaignUseCase;
import dev.ngb.mailroom.sending.application.campaign.command.TestCampaignUseCase;
import dev.ngb.mailroom.sending.application.campaign.command.UpdateCampaignUseCase;
import dev.ngb.mailroom.sending.application.campaign.query.CampaignListing;
import dev.ngb.mailroom.sending.application.campaign.query.CampaignQueries;
import dev.ngb.mailroom.sending.application.campaign.query.CampaignStatsQueries;
import dev.ngb.mailroom.sending.application.campaign.query.CampaignStatsView;
import dev.ngb.mailroom.sending.application.campaign.query.CampaignView;
import dev.ngb.mailroom.sending.domain.campaign.Campaign;
import dev.ngb.mailroom.sending.domain.campaign.CampaignTargetType;
import dev.ngb.mailroom.shared.MailroomPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Campaigns and their state machine. */
@Tag(
        name = "Campaigns",
        description =
                "Bulk email campaigns and their state machine: draft, schedule, send, pause, resume, cancel, test and "
                + "statistics.")
@RestController
@RequestMapping("/api/v1/campaigns")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
class CampaignController {

    private final CreateCampaignUseCase create;
    private final UpdateCampaignUseCase update;
    private final ScheduleCampaignUseCase schedule;
    private final StartCampaignUseCase start;
    private final ChangeCampaignStateUseCase change;
    private final TestCampaignUseCase test;
    private final CampaignQueries queries;
    private final CampaignStatsQueries stats;

    CampaignController(
            CreateCampaignUseCase create,
            UpdateCampaignUseCase update,
            ScheduleCampaignUseCase schedule,
            StartCampaignUseCase start,
            ChangeCampaignStateUseCase change,
            TestCampaignUseCase test,
            CampaignQueries queries,
            CampaignStatsQueries stats) {
        this.create = create;
        this.update = update;
        this.schedule = schedule;
        this.start = start;
        this.change = change;
        this.test = test;
        this.queries = queries;
        this.stats = stats;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link CampaignListing}. */
    @Operation(
            summary = "List campaigns",
            description =
                    "Pages through the tenant's campaigns, with the list-query contract's filtering, sorting and paging. ")
    @GetMapping
    @Listing(CampaignListing.class)
    @RequiresPermission(MailroomPermissions.CAMPAIGN_READ)
    Page<CampaignResponse> list(ListQuery query) {
        return queries.list(query).map(CampaignResponse::of);
    }

    @Operation(
            summary = "Create a campaign",
            description =
                    "Creates a campaign in `DRAFT` that targets a group or segment (`targetType`, `targetId`) with the "
                    + "template `templateKey` and the sender `fromKey`; it pins the template revision it will send. Answers "
                    + "`201`.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(MailroomPermissions.CAMPAIGN_MANAGE)
    CampaignResponse create(@RequestBody CampaignRequest request) {
        return CampaignResponse.of(create.execute(new CreateCampaignUseCase.Command(
                request.name(),
                request.templateKey(),
                CampaignTargetType.valueOf(request.targetType()),
                request.targetId(),
                request.fromKey())));
    }

    @Operation(
            summary = "Get a campaign",
            description =
                    "Returns one campaign with its state; its version is also the response's `ETag`. An unknown id "
                    + "answers `404`.")
    @GetMapping("/{id}")
    @RequiresPermission(MailroomPermissions.CAMPAIGN_READ)
    ResponseEntity<CampaignResponse> get(@PathVariable UUID id) {
        CampaignView view = queries.get(id);
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(view.version())).body(CampaignResponse.of(view));
    }

    @Operation(
            summary = "Edit a draft campaign",
            description =
                    "Edits a campaign that is still in `DRAFT` and re-pins the template revision it will send. Pass the "
                    + "current `ETag` in `If-Match`; a stale version is refused.")
    @PutMapping("/{id}")
    @ConditionalUpdate
    @RequiresPermission(MailroomPermissions.CAMPAIGN_MANAGE)
    ResponseEntity<CampaignResponse> update(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody CampaignRequest request) {
        Campaign campaign = update.execute(
                id,
                new UpdateCampaignUseCase.Change(
                        request.name(),
                        request.templateKey(),
                        CampaignTargetType.valueOf(request.targetType()),
                        request.targetId(),
                        request.fromKey()),
                ExpectedVersion.fromIfMatch(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(campaign.version()))
                .body(CampaignResponse.of(campaign));
    }

    @Operation(
            summary = "Schedule a campaign",
            description =
                    "Schedules a `DRAFT` campaign to start at `scheduledAt`, which must be in the future. When the time "
                    + "comes the campaign enters `SENDING` and its audience is snapshotted once. Pass the current `ETag` in "
                    + "`If-Match`; a stale version is refused.")
    @PostMapping("/{id}/schedule")
    @ConditionalUpdate
    @RequiresPermission(MailroomPermissions.CAMPAIGN_MANAGE)
    ResponseEntity<CampaignResponse> schedule(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody ScheduleRequest request) {
        Campaign campaign = schedule.execute(id, request.scheduledAt(), ExpectedVersion.fromIfMatch(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(campaign.version()))
                .body(CampaignResponse.of(campaign));
    }

    @Operation(
            summary = "Send a campaign now",
            description =
                    "Starts the campaign immediately: it enters `SENDING` and its audience is snapshotted once. "
                    + "Recipients are then published at the tenant's campaign rate.")
    @PostMapping("/{id}/send")
    @RequiresPermission(MailroomPermissions.CAMPAIGN_MANAGE)
    CampaignResponse send(@PathVariable UUID id) {
        return CampaignResponse.of(start.execute(id, ExpectedVersion.any()));
    }

    @Operation(
            summary = "Pause a campaign",
            description =
                    "Pauses a sending campaign so no further recipients are published. A campaign in a state that cannot "
                    + "be paused answers `409`.")
    @PostMapping("/{id}/pause")
    @RequiresPermission(MailroomPermissions.CAMPAIGN_MANAGE)
    CampaignResponse pause(@PathVariable UUID id) {
        return CampaignResponse.of(change.execute(id, ChangeCampaignStateUseCase.Action.PAUSE));
    }

    @Operation(
            summary = "Resume a campaign",
            description =
                    "Resumes a paused campaign. A campaign in a state that cannot be resumed answers `409`. Requires "
                    + "`mailroom:campaign:manage`.")
    @PostMapping("/{id}/resume")
    @RequiresPermission(MailroomPermissions.CAMPAIGN_MANAGE)
    CampaignResponse resume(@PathVariable UUID id) {
        return CampaignResponse.of(change.execute(id, ChangeCampaignStateUseCase.Action.RESUME));
    }

    @Operation(
            summary = "Cancel a campaign",
            description =
                    "Cancels the campaign so it sends no more. A campaign in a state that cannot be cancelled answers "
                    + "`409`.")
    @PostMapping("/{id}/cancel")
    @RequiresPermission(MailroomPermissions.CAMPAIGN_MANAGE)
    CampaignResponse cancel(@PathVariable UUID id) {
        return CampaignResponse.of(change.execute(id, ChangeCampaignStateUseCase.Action.CANCEL));
    }

    @Operation(
            summary = "Send a campaign test",
            description =
                    "Sends a sample of the campaign to up to five explicit `addresses` as transactional mail. A test send "
                    + "stores no campaign or receiver id, so it never counts in the campaign's statistics and never touches "
                    + "its snapshot.")
    @PostMapping("/{id}/test")
    @RequiresPermission(MailroomPermissions.CAMPAIGN_MANAGE)
    List<TestCampaignUseCase.Result> test(@PathVariable UUID id, @RequestBody TestRequest request) {
        return test.execute(id, request.addresses());
    }

    @Operation(
            summary = "Get a campaign's statistics",
            description =
                    "Returns the campaign's delivery and engagement counts.")
    @GetMapping("/{id}/stats")
    @RequiresPermission(MailroomPermissions.CAMPAIGN_READ)
    CampaignStatsView stats(@PathVariable UUID id) {
        return stats.stats(id);
    }

    record CampaignRequest(String name, String templateKey, String targetType, UUID targetId, String fromKey) {}

    record ScheduleRequest(Instant scheduledAt) {}

    record TestRequest(List<String> addresses) {}

    record CampaignResponse(
            UUID id,
            String name,
            String status,
            UUID templateId,
            int templateRevision,
            String targetType,
            UUID targetId,
            String fromKey,
            String fromName,
            String replyTo,
            Instant scheduledAt,
            Instant startedAt,
            Instant completedAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        static CampaignResponse of(Campaign campaign) {
            return new CampaignResponse(
                    campaign.id(), campaign.name(), campaign.status().name(), campaign.templateId(),
                    campaign.templateRevision(), campaign.targetType().name(), campaign.targetId(),
                    campaign.fromKey(), campaign.fromName(), campaign.replyTo(), campaign.scheduledAt(),
                    campaign.startedAt(), campaign.completedAt(), campaign.version(), campaign.createdAt(),
                    campaign.updatedAt());
        }

        static CampaignResponse of(CampaignView view) {
            return new CampaignResponse(
                    view.id(), view.name(), view.status(), view.templateId(), view.templateRevision(),
                    view.targetType(), view.targetId(), view.fromKey(), view.fromName(), view.replyTo(),
                    view.scheduledAt(), view.startedAt(), view.completedAt(), view.version(),
                    view.createdAt(), view.updatedAt());
        }
    }
}
