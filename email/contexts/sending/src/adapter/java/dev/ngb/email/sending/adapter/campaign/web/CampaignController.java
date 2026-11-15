package dev.ngb.email.sending.adapter.campaign.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.email.sending.application.campaign.command.ChangeCampaignStateUseCase;
import dev.ngb.email.sending.application.campaign.command.CreateCampaignUseCase;
import dev.ngb.email.sending.application.campaign.command.ScheduleCampaignUseCase;
import dev.ngb.email.sending.application.campaign.command.StartCampaignUseCase;
import dev.ngb.email.sending.application.campaign.command.TestCampaignUseCase;
import dev.ngb.email.sending.application.campaign.command.UpdateCampaignUseCase;
import dev.ngb.email.sending.application.campaign.query.CampaignQueries;
import dev.ngb.email.sending.application.campaign.query.CampaignStatsQueries;
import dev.ngb.email.sending.application.campaign.query.CampaignStatsView;
import dev.ngb.email.sending.application.campaign.query.CampaignView;
import dev.ngb.email.sending.domain.campaign.Campaign;
import dev.ngb.email.sending.domain.campaign.CampaignTargetType;
import dev.ngb.email.shared.EmailPermissions;
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
@RestController
@RequestMapping("/api/v1/campaigns")
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

    @GetMapping
    @RequiresPermission(EmailPermissions.CAMPAIGN_READ)
    List<CampaignResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID after,
            @RequestParam(required = false) Integer limit) {
        return queries.list(status, after, limit).stream().map(CampaignResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(EmailPermissions.CAMPAIGN_MANAGE)
    CampaignResponse create(@RequestBody CampaignRequest request) {
        return CampaignResponse.of(create.execute(new CreateCampaignUseCase.Command(
                request.name(),
                request.templateKey(),
                CampaignTargetType.valueOf(request.targetType()),
                request.targetId(),
                request.fromKey())));
    }

    @GetMapping("/{id}")
    @RequiresPermission(EmailPermissions.CAMPAIGN_READ)
    ResponseEntity<CampaignResponse> get(@PathVariable UUID id) {
        CampaignView view = queries.get(id);
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(view.version())).body(CampaignResponse.of(view));
    }

    @PutMapping("/{id}")
    @ConditionalUpdate
    @RequiresPermission(EmailPermissions.CAMPAIGN_MANAGE)
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

    @PostMapping("/{id}/schedule")
    @ConditionalUpdate
    @RequiresPermission(EmailPermissions.CAMPAIGN_MANAGE)
    ResponseEntity<CampaignResponse> schedule(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody ScheduleRequest request) {
        Campaign campaign = schedule.execute(id, request.scheduledAt(), ExpectedVersion.fromIfMatch(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(campaign.version()))
                .body(CampaignResponse.of(campaign));
    }

    @PostMapping("/{id}/send")
    @RequiresPermission(EmailPermissions.CAMPAIGN_MANAGE)
    CampaignResponse send(@PathVariable UUID id) {
        return CampaignResponse.of(start.execute(id, ExpectedVersion.any()));
    }

    @PostMapping("/{id}/pause")
    @RequiresPermission(EmailPermissions.CAMPAIGN_MANAGE)
    CampaignResponse pause(@PathVariable UUID id) {
        return CampaignResponse.of(change.execute(id, ChangeCampaignStateUseCase.Action.PAUSE));
    }

    @PostMapping("/{id}/resume")
    @RequiresPermission(EmailPermissions.CAMPAIGN_MANAGE)
    CampaignResponse resume(@PathVariable UUID id) {
        return CampaignResponse.of(change.execute(id, ChangeCampaignStateUseCase.Action.RESUME));
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission(EmailPermissions.CAMPAIGN_MANAGE)
    CampaignResponse cancel(@PathVariable UUID id) {
        return CampaignResponse.of(change.execute(id, ChangeCampaignStateUseCase.Action.CANCEL));
    }

    @PostMapping("/{id}/test")
    @RequiresPermission(EmailPermissions.CAMPAIGN_MANAGE)
    List<TestCampaignUseCase.Result> test(@PathVariable UUID id, @RequestBody TestRequest request) {
        return test.execute(id, request.addresses());
    }

    @GetMapping("/{id}/stats")
    @RequiresPermission(EmailPermissions.CAMPAIGN_READ)
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
