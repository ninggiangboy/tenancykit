package dev.ngb.booking.catalog.adapter.resource.web;

import dev.ngb.booking.catalog.application.resource.command.ArchiveResourceUseCase;
import dev.ngb.booking.catalog.application.resource.command.CreateResourceUseCase;
import dev.ngb.booking.catalog.application.resource.command.PatchResourceUseCase;
import dev.ngb.booking.catalog.application.resource.query.ResourceListing;
import dev.ngb.booking.catalog.application.resource.query.ResourceQueries;
import dev.ngb.booking.catalog.application.resource.query.ResourceView;
import dev.ngb.booking.catalog.application.resource.service.ResourceRules;
import dev.ngb.booking.catalog.domain.resource.Resource;
import dev.ngb.booking.shared.BookingPermissions;
import dev.ngb.booking.shared.ConditionalRequests;
import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Tenant's resources: what it may book and the zone its wall-clock hours are read in.
 *
 * <p>The code is chosen once and the zone never moves, so a booking that meant a time in one zone cannot silently mean
 * another. {@code PATCH} and {@code DELETE} are conditional on {@code If-Match}, and every response carries the version
 * as an {@code ETag}. A referenced resource is archived, never deleted.
 */
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class ResourceController {

    /** One resource as the Tenant sends it; a null seat count means the resource is not seat-limited. */
    public record ResourceCreateRequest(String code, String name, String zone, Integer seats) {}

    /** The properties to change: a null one is left as it is. */
    public record ResourcePatchRequest(String name, Integer seats, String status) {}

    public record ResourceResponse(
            String id,
            String code,
            String name,
            String zone,
            Integer seats,
            String status,
            long version) {

        static ResourceResponse of(Resource resource) {
            return new ResourceResponse(
                    resource.id().toString(),
                    resource.code(),
                    resource.name(),
                    resource.zone(),
                    resource.seats(),
                    resource.status().name(),
                    resource.version());
        }

        static ResourceResponse ofView(ResourceView view) {
            return new ResourceResponse(
                    view.id().toString(),
                    view.code(),
                    view.name(),
                    view.zone(),
                    view.seats(),
                    view.status(),
                    view.version());
        }
    }

    private final CreateResourceUseCase creates;
    private final PatchResourceUseCase patches;
    private final ArchiveResourceUseCase archives;
    private final ResourceQueries resources;

    public ResourceController(
            CreateResourceUseCase creates,
            PatchResourceUseCase patches,
            ArchiveResourceUseCase archives,
            ResourceQueries resources) {
        this.creates = creates;
        this.patches = patches;
        this.archives = archives;
        this.resources = resources;
    }

    /** The list contract's paging and sort, narrowed by the code, the zone and the status. */
    @GetMapping("/resources")
    @Listing(ResourceListing.class)
    @RequiresPermission(BookingPermissions.RESOURCE_READ)
    public Page<ResourceResponse> listResources(ListQuery query) {
        return resources.list(query).map(ResourceResponse::ofView);
    }

    /** Creates the resource and answers its version as the {@code ETag}. */
    @PostMapping("/resources")
    @RequiresPermission(BookingPermissions.RESOURCE_MANAGE)
    public ResponseEntity<ResourceResponse> createResource(@RequestBody ResourceCreateRequest request) {
        Resource resource = creates.execute(new CreateResourceUseCase.Command(
                request.code(), request.name(), request.zone(), request.seats()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ExpectedVersion.etag(resource.version()))
                .body(ResourceResponse.of(resource));
    }

    @GetMapping("/resources/{resourceId}")
    @RequiresPermission(BookingPermissions.RESOURCE_READ)
    public ResponseEntity<ResourceResponse> getResource(@PathVariable UUID resourceId) {
        ResourceView resource = resources.get(resourceId);
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(resource.version()))
                .body(ResourceResponse.ofView(resource));
    }

    /** Changes only the properties that are present; the version the caller pinned must still be the current one. */
    @PatchMapping("/resources/{resourceId}")
    @ConditionalUpdate
    @RequiresPermission(BookingPermissions.RESOURCE_MANAGE)
    public ResponseEntity<ResourceResponse> patchResource(
            @PathVariable UUID resourceId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody ResourcePatchRequest request) {
        Resource resource = patches.execute(
                resourceId,
                new PatchResourceUseCase.Change(
                        request.name(), request.seats(), ResourceRules.status(request.status())),
                ConditionalRequests.requireIfMatch(ifMatch));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(resource.version()))
                .body(ResourceResponse.of(resource));
    }

    /** Archives a referenced resource, deletes an unreferenced one; either way the answer is an empty {@code 204}. */
    @DeleteMapping("/resources/{resourceId}")
    @ConditionalUpdate
    @RequiresPermission(BookingPermissions.RESOURCE_MANAGE)
    public ResponseEntity<Void> archiveResource(
            @PathVariable UUID resourceId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        archives.execute(resourceId, ConditionalRequests.requireIfMatch(ifMatch));
        return ResponseEntity.noContent().build();
    }
}
