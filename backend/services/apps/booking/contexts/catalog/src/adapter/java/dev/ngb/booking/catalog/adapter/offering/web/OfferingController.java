package dev.ngb.booking.catalog.adapter.offering.web;

import dev.ngb.booking.catalog.application.offering.command.ArchiveOfferingUseCase;
import dev.ngb.booking.catalog.application.offering.command.CreateOfferingUseCase;
import dev.ngb.booking.catalog.application.offering.command.PatchOfferingUseCase;
import dev.ngb.booking.catalog.application.offering.command.ReplaceCancellationWindowsUseCase;
import dev.ngb.booking.catalog.application.offering.command.ReplaceRequirementsUseCase;
import dev.ngb.booking.catalog.application.offering.query.CancellationWindowView;
import dev.ngb.booking.catalog.application.offering.query.OfferingListing;
import dev.ngb.booking.catalog.application.offering.query.OfferingQueries;
import dev.ngb.booking.catalog.application.offering.query.OfferingView;
import dev.ngb.booking.catalog.application.offering.query.RequirementView;
import dev.ngb.booking.catalog.application.offering.service.OfferingRules;
import dev.ngb.booking.catalog.domain.offering.CancellationWindow;
import dev.ngb.booking.catalog.domain.offering.Offering;
import dev.ngb.booking.catalog.domain.offering.OfferingKind;
import dev.ngb.booking.catalog.domain.offering.OfferingStatus;
import dev.ngb.booking.catalog.domain.offering.QueueMode;
import dev.ngb.booking.catalog.domain.offering.Requirement;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.shared.ConditionalRequests;
import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Offerings: what a Tenant sells, the requirements an interval offering places on a booking, and the cancellation
 * thresholds it names. A code is chosen once and immutable; the settings change through a PATCH that pins the version,
 * archiving is a DELETE, and requirements and thresholds are replaced whole through their own endpoints.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class OfferingController {

    public record OfferingCreateRequest(
            String code,
            String name,
            OfferingKind kind,
            Integer durationMin,
            Integer durationMax,
            Integer durationStep,
            Integer slotStep,
            Integer bufferBefore,
            Integer bufferAfter,
            Integer leadTimeSeconds,
            Integer horizonDays,
            Integer holdTtlSeconds,
            Integer maxQty,
            Boolean publicShowsRemaining,
            QueueMode queueMode,
            Integer admitPerSecond,
            Integer admitWindowSeconds) {}

    public record OfferingPatchRequest(
            String name,
            OfferingStatus status,
            Integer durationMin,
            Integer durationMax,
            Integer durationStep,
            Integer slotStep,
            Integer bufferBefore,
            Integer bufferAfter,
            Integer leadTimeSeconds,
            Integer horizonDays,
            Integer holdTtlSeconds,
            Integer maxQty,
            Boolean publicShowsRemaining,
            QueueMode queueMode,
            Integer admitPerSecond,
            Integer admitWindowSeconds) {}

    public record RequirementRequest(String slotCode, UUID resourceId, UUID groupId) {}

    public record RequirementsReplaceRequest(List<RequirementRequest> requirements) {}

    public record CancellationWindowRequest(Integer minutesBeforeStart, String code) {}

    public record CancellationWindowsReplaceRequest(List<CancellationWindowRequest> windows) {}

    public record RequirementResponse(String slotCode, UUID resourceId, UUID groupId) {

        static RequirementResponse of(Requirement requirement) {
            return new RequirementResponse(requirement.slotCode(), requirement.resourceId(), requirement.groupId());
        }

        static RequirementResponse ofView(RequirementView view) {
            return new RequirementResponse(view.slotCode(), view.resourceId(), view.groupId());
        }
    }

    public record CancellationWindowResponse(int minutesBeforeStart, String code) {

        static CancellationWindowResponse of(CancellationWindow window) {
            return new CancellationWindowResponse(window.minutesBeforeStart(), window.code());
        }

        static CancellationWindowResponse ofView(CancellationWindowView view) {
            return new CancellationWindowResponse(view.minutesBeforeStart(), view.code());
        }
    }

    public record OfferingResponse(
            String id,
            String code,
            String name,
            String kind,
            String status,
            Integer durationMin,
            Integer durationMax,
            Integer durationStep,
            Integer slotStep,
            Integer bufferBefore,
            Integer bufferAfter,
            Integer leadTimeSeconds,
            Integer horizonDays,
            Integer holdTtlSeconds,
            Integer maxQty,
            Boolean publicShowsRemaining,
            String queueMode,
            Integer admitPerSecond,
            Integer admitWindowSeconds,
            long version,
            List<RequirementResponse> requirements,
            List<CancellationWindowResponse> cancellationWindows) {

        static OfferingResponse of(Offering offering) {
            return new OfferingResponse(
                    offering.id().toString(),
                    offering.code(),
                    offering.name(),
                    offering.kind().name(),
                    offering.status().name(),
                    offering.durationMin(),
                    offering.durationMax(),
                    offering.durationStep(),
                    offering.slotStep(),
                    offering.bufferBefore(),
                    offering.bufferAfter(),
                    offering.leadTimeSeconds(),
                    offering.horizonDays(),
                    offering.holdTtlSeconds(),
                    offering.maxQty(),
                    offering.publicShowsRemaining(),
                    offering.queueMode().name(),
                    offering.admitPerSecond(),
                    offering.admitWindowSeconds(),
                    offering.version(),
                    offering.requirements().stream().map(RequirementResponse::of).toList(),
                    offering.cancellationWindows().stream().map(CancellationWindowResponse::of).toList());
        }

        static OfferingResponse ofView(OfferingView view) {
            return new OfferingResponse(
                    view.id().toString(),
                    view.code(),
                    view.name(),
                    view.kind().name(),
                    view.status().name(),
                    view.durationMin(),
                    view.durationMax(),
                    view.durationStep(),
                    view.slotStep(),
                    view.bufferBefore(),
                    view.bufferAfter(),
                    view.leadTimeSeconds(),
                    view.horizonDays(),
                    view.holdTtlSeconds(),
                    view.maxQty(),
                    view.publicShowsRemaining(),
                    view.queueMode().name(),
                    view.admitPerSecond(),
                    view.admitWindowSeconds(),
                    view.version(),
                    view.requirements().stream().map(RequirementResponse::ofView).toList(),
                    view.cancellationWindows().stream().map(CancellationWindowResponse::ofView).toList());
        }
    }

    public record RequirementsResponse(List<RequirementResponse> requirements) {}

    public record CancellationWindowsResponse(List<CancellationWindowResponse> windows) {}

    private final CreateOfferingUseCase creates;
    private final PatchOfferingUseCase patches;
    private final ArchiveOfferingUseCase archives;
    private final ReplaceRequirementsUseCase requirements;
    private final ReplaceCancellationWindowsUseCase windows;
    private final OfferingQueries offerings;

    public OfferingController(
            CreateOfferingUseCase creates,
            PatchOfferingUseCase patches,
            ArchiveOfferingUseCase archives,
            ReplaceRequirementsUseCase requirements,
            ReplaceCancellationWindowsUseCase windows,
            OfferingQueries offerings) {
        this.creates = creates;
        this.patches = patches;
        this.archives = archives;
        this.requirements = requirements;
        this.windows = windows;
        this.offerings = offerings;
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link OfferingListing}. */
    @GetMapping("/offerings")
    @Listing(OfferingListing.class)
    @RequiresPermission(BookingPermissions.OFFERING_READ)
    public Page<OfferingResponse> listOfferings(ListQuery query) {
        return offerings.list(query).map(OfferingResponse::ofView);
    }

    @PostMapping("/offerings")
    @RequiresPermission(BookingPermissions.OFFERING_MANAGE)
    public ResponseEntity<OfferingResponse> createOffering(@RequestBody OfferingCreateRequest request) {
        Offering offering = creates.execute(new CreateOfferingUseCase.Command(
                request.code(),
                request.name(),
                request.kind(),
                request.durationMin(),
                request.durationMax(),
                request.durationStep(),
                request.slotStep(),
                request.bufferBefore(),
                request.bufferAfter(),
                request.leadTimeSeconds(),
                request.horizonDays(),
                request.holdTtlSeconds(),
                request.maxQty(),
                request.publicShowsRemaining(),
                request.queueMode(),
                request.admitPerSecond(),
                request.admitWindowSeconds()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ExpectedVersion.etag(offering.version()))
                .body(OfferingResponse.of(offering));
    }

    @GetMapping("/offerings/{offeringId}")
    @RequiresPermission(BookingPermissions.OFFERING_READ)
    public ResponseEntity<OfferingResponse> getOffering(@PathVariable String offeringId) {
        OfferingView offering = offerings.get(UUID.fromString(offeringId));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(offering.version()))
                .body(OfferingResponse.ofView(offering));
    }

    /** Changes only the properties that are present; the version the caller pinned must still be the current one. */
    @PatchMapping("/offerings/{offeringId}")
    @ConditionalUpdate
    @RequiresPermission(BookingPermissions.OFFERING_MANAGE)
    public ResponseEntity<OfferingResponse> patchOffering(
            @PathVariable String offeringId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody OfferingPatchRequest request) {
        Offering offering = patches.execute(
                UUID.fromString(offeringId),
                new PatchOfferingUseCase.Change(
                        request.name(),
                        request.status(),
                        request.durationMin(),
                        request.durationMax(),
                        request.durationStep(),
                        request.slotStep(),
                        request.bufferBefore(),
                        request.bufferAfter(),
                        request.leadTimeSeconds(),
                        request.horizonDays(),
                        request.holdTtlSeconds(),
                        request.maxQty(),
                        request.publicShowsRemaining(),
                        request.queueMode(),
                        request.admitPerSecond(),
                        request.admitWindowSeconds()),
                ConditionalRequests.requireIfMatch(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(offering.version()))
                .body(OfferingResponse.of(offering));
    }

    /** An offering is archived, never removed. A repeated archive changes nothing and answers {@code 204} again. */
    @DeleteMapping("/offerings/{offeringId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ConditionalUpdate
    @RequiresPermission(BookingPermissions.OFFERING_MANAGE)
    public void archiveOffering(
            @PathVariable String offeringId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        archives.execute(UUID.fromString(offeringId), ConditionalRequests.requireIfMatch(ifMatch));
    }

    @PutMapping("/offerings/{offeringId}/requirements")
    @RequiresPermission(BookingPermissions.OFFERING_MANAGE)
    public RequirementsResponse replaceRequirements(
            @PathVariable String offeringId, @RequestBody RequirementsReplaceRequest request) {
        List<Requirement> stored = requirements.execute(
                UUID.fromString(offeringId),
                new ReplaceRequirementsUseCase.Command(requirements(request.requirements())));
        return new RequirementsResponse(stored.stream().map(RequirementResponse::of).toList());
    }

    @PutMapping("/offerings/{offeringId}/cancellation-windows")
    @RequiresPermission(BookingPermissions.OFFERING_MANAGE)
    public CancellationWindowsResponse replaceCancellationWindows(
            @PathVariable String offeringId, @RequestBody CancellationWindowsReplaceRequest request) {
        List<CancellationWindow> stored = windows.execute(
                UUID.fromString(offeringId),
                new ReplaceCancellationWindowsUseCase.Command(windows(request.windows())));
        return new CancellationWindowsResponse(stored.stream().map(CancellationWindowResponse::of).toList());
    }

    private static List<Requirement> requirements(List<RequirementRequest> raw) {
        if (raw == null) {
            return List.of();
        }
        List<Requirement> requirements = new ArrayList<>(raw.size());
        for (RequirementRequest request : raw) {
            requirements.add(OfferingRules.requirement(request.slotCode(), request.resourceId(), request.groupId()));
        }
        return requirements;
    }

    private static List<CancellationWindow> windows(List<CancellationWindowRequest> raw) {
        if (raw == null) {
            return List.of();
        }
        List<CancellationWindow> windows = new ArrayList<>(raw.size());
        for (CancellationWindowRequest request : raw) {
            windows.add(OfferingRules.window(request.minutesBeforeStart(), request.code()));
        }
        return windows;
    }
}
