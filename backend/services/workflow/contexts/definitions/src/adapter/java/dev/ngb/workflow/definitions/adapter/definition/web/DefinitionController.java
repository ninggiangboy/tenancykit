package dev.ngb.workflow.definitions.adapter.definition.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.definitions.application.definition.command.CreateDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.PublishDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.RetireDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.SimulateDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.UpdateDraftUseCase;
import dev.ngb.workflow.definitions.application.definition.exception.PreconditionRequiredException;
import dev.ngb.workflow.definitions.application.definition.query.DefinitionListing;
import dev.ngb.workflow.definitions.application.definition.query.DefinitionQueries;
import dev.ngb.workflow.definitions.application.definition.query.DefinitionView;
import dev.ngb.workflow.definitions.application.definition.query.VersionView;
import dev.ngb.workflow.definitions.application.definition.service.GraphSimulation;
import dev.ngb.workflow.definitions.domain.definition.Definition;
import dev.ngb.workflow.definitions.domain.problem.Problem;
import dev.ngb.workflow.shared.WorkflowPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A Tenant's workflow definitions: create one, edit its draft under {@code If-Match}, publish an immutable version,
 * retire it, read its versions, and simulate the draft without side effects.
 *
 * <p>The draft is what an author is still writing, so a structurally wrong one is stored and its problems returned;
 * publish refuses it with 422 and every problem by pointer. Every response that names a definition carries its version
 * as an {@code ETag}.
 */
@Tag(
        name = "Workflow definitions",
        description =
                "Create a definition, edit its draft, publish immutable versions, retire it and simulate the draft.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class DefinitionController {

    public record CreateDefinitionRequest(String code, String name) {}

    /** The graph as the author wrote it; the checker is what says whether it can be published. */
    public record DraftRequest(Object graph) {}

    /** A sample payload and canned step outputs for the side-effect-free walk. */
    public record SimulateRequest(Map<String, Object> input, Map<String, Object> steps) {}

    public record ProblemResponse(String pointer, String code, String message) {

        static ProblemResponse of(Problem problem) {
            return new ProblemResponse(problem.pointer(), problem.code(), problem.message());
        }
    }

    public record DefinitionResponse(
            String id,
            String code,
            String name,
            String status,
            int latestVersion,
            Instant createdAt,
            Instant updatedAt) {

        static DefinitionResponse of(Definition definition) {
            return new DefinitionResponse(
                    definition.id().toString(),
                    definition.code(),
                    definition.name(),
                    definition.status().name(),
                    definition.latestVersion(),
                    definition.createdAt(),
                    definition.updatedAt());
        }

        static DefinitionResponse ofView(DefinitionView view) {
            return new DefinitionResponse(
                    view.id().toString(),
                    view.code(),
                    view.name(),
                    view.status().name(),
                    view.latestVersion(),
                    view.createdAt(),
                    view.updatedAt());
        }
    }

    /** The draft write result: the definition's new version in the {@code ETag}, and what still must be fixed. */
    public record DraftResponse(DefinitionResponse definition, List<ProblemResponse> problems) {}

    public record VersionResponse(
            String id,
            String definitionId,
            int version,
            Object graph,
            String contentHash,
            Instant publishedAt,
            String publishedBy) {

        static VersionResponse of(VersionView view) {
            return new VersionResponse(
                    view.id().toString(),
                    view.definitionId().toString(),
                    view.version(),
                    view.graph(),
                    view.contentHash(),
                    view.publishedAt(),
                    view.publishedBy());
        }
    }

    public record DecisionResponse(String node, Integer caseIndex, String next) {}

    public record SimulationResponse(
            List<String> path,
            List<DecisionResponse> decisions,
            Map<String, Object> variables,
            String stoppedAt,
            List<ProblemResponse> problems) {}

    private final CreateDefinitionUseCase create;
    private final UpdateDraftUseCase drafts;
    private final PublishDefinitionUseCase publish;
    private final RetireDefinitionUseCase retire;
    private final SimulateDefinitionUseCase simulate;
    private final DefinitionQueries definitionQueries;
    private final CurrentActor currentActor;

    public DefinitionController(
            CreateDefinitionUseCase create,
            UpdateDraftUseCase drafts,
            PublishDefinitionUseCase publish,
            RetireDefinitionUseCase retire,
            SimulateDefinitionUseCase simulate,
            DefinitionQueries definitionQueries,
            CurrentActor currentActor) {
        this.create = create;
        this.drafts = drafts;
        this.publish = publish;
        this.retire = retire;
        this.simulate = simulate;
        this.definitionQueries = definitionQueries;
        this.currentActor = currentActor;
    }

    @Operation(
            summary = "Create a workflow definition",
            description =
                    "Creates a definition with a `code` and a name and an empty draft; the graph arrives through the "
                    + "draft endpoint. Requires `workflow:definition:manage`.")
    @PostMapping("/definitions")
    @RequiresPermission(WorkflowPermissions.DEFINITION_MANAGE)
    public ResponseEntity<DefinitionResponse> createDefinition(
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody CreateDefinitionRequest request) {
        Definition definition = create.execute(new CreateDefinitionUseCase.Command(request.code(), request.name()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ExpectedVersion.etag(definition.version()))
                .body(DefinitionResponse.of(definition));
    }

    /** Filter, sort and paging follow the list-query contract; the documented parameters come from {@link DefinitionListing}. */
    @Operation(
            summary = "List workflow definitions",
            description =
                    "Pages through the tenant's definitions, with the list-query contract's filtering, sorting and "
                    + "paging. Requires `workflow:definition:read`.")
    @GetMapping("/definitions")
    @Listing(DefinitionListing.class)
    @RequiresPermission(WorkflowPermissions.DEFINITION_READ)
    public Page<DefinitionResponse> listDefinitions(ListQuery query) {
        return definitionQueries.list(query).map(DefinitionResponse::ofView);
    }

    @Operation(
            summary = "Get a workflow definition",
            description =
                    "Returns one definition; its version is also the response's `ETag`. An unknown id answers `404`. "
                    + "Requires `workflow:definition:read`.")
    @GetMapping("/definitions/{id}")
    @RequiresPermission(WorkflowPermissions.DEFINITION_READ)
    public ResponseEntity<DefinitionResponse> getDefinition(@PathVariable String id) {
        DefinitionView definition = definitionQueries.get(uuid(id));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(definition.version()))
                .body(DefinitionResponse.ofView(definition));
    }

    /** Replaces the whole draft graph; the version the author pinned must still be the current one. */
    @Operation(
            summary = "Replace a definition's draft",
            description =
                    "Replaces the whole draft `graph`. A structurally wrong draft is stored as sent and its problems are "
                    + "returned, because a draft is what an author is still writing; only publish refuses one. Pass the "
                    + "current `ETag` in `If-Match`; a stale version is refused with `412`. Requires "
                    + "`workflow:definition:manage`.")
    @PutMapping("/definitions/{id}/draft")
    @ConditionalUpdate
    @RequiresPermission(WorkflowPermissions.DEFINITION_MANAGE)
    public ResponseEntity<DraftResponse> putDraft(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId,
            @RequestBody DraftRequest request) {
        UpdateDraftUseCase.Result result = drafts.execute(
                new UpdateDraftUseCase.Command(uuid(id), request.graph(), required(ifMatch)));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(result.definition().version()))
                .body(new DraftResponse(
                        DefinitionResponse.of(result.definition()),
                        result.problems().stream().map(ProblemResponse::of).toList()));
    }

    /** Freezes the draft into the next version number; an invalid draft answers 422 with every problem. */
    @Operation(
            summary = "Publish a definition",
            description =
                    "Freezes the draft into the next immutable version number. An invalid draft answers `422` with every "
                    + "problem by pointer. Pass the current `ETag` in `If-Match`; a stale version is refused with `412`. "
                    + "Requires `workflow:definition:manage`.")
    @PostMapping("/definitions/{id}/publish")
    @ConditionalUpdate
    @RequiresPermission(WorkflowPermissions.DEFINITION_MANAGE)
    public ResponseEntity<VersionResponse> publishDefinition(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        PublishDefinitionUseCase.Result result = publish.execute(new PublishDefinitionUseCase.Command(
                uuid(id), required(ifMatch), currentActor.get().auditName()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(ExpectedVersion.etag(result.definition().version()))
                .body(new VersionResponse(
                        result.version().id().toString(),
                        result.version().definitionId().toString(),
                        result.version().number(),
                        result.version().graph(),
                        result.version().contentHash(),
                        result.version().publishedAt(),
                        result.version().publishedBy()));
    }

    /** Stops new instances; the published versions and the instances pinned to them are untouched. */
    @Operation(
            summary = "Retire a definition",
            description =
                    "Stops new instances from starting. The published versions and the instances pinned to them are "
                    + "untouched and keep running. Pass the current `ETag` in `If-Match`; a stale version is refused with "
                    + "`412`. Requires `workflow:definition:manage`.")
    @PostMapping("/definitions/{id}/retire")
    @ConditionalUpdate
    @RequiresPermission(WorkflowPermissions.DEFINITION_MANAGE)
    public ResponseEntity<DefinitionResponse> retireDefinition(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.REQUEST_ID, required = false) String requestId) {
        Definition definition = retire.execute(new RetireDefinitionUseCase.Command(uuid(id), required(ifMatch)));
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(definition.version()))
                .body(DefinitionResponse.of(definition));
    }

    @Operation(
            summary = "List a definition's versions",
            description =
                    "Returns the definition's published versions. Requires `workflow:definition:read`.")
    @GetMapping("/definitions/{id}/versions")
    @RequiresPermission(WorkflowPermissions.DEFINITION_READ)
    public List<VersionResponse> listVersions(@PathVariable String id) {
        return definitionQueries.versions(uuid(id)).stream().map(VersionResponse::of).toList();
    }

    @Operation(
            summary = "Get a published version",
            description =
                    "Returns published version `version` of the definition with its graph. A published version never "
                    + "changes. An unknown definition or version answers `404`. Requires `workflow:definition:read`.")
    @GetMapping("/definitions/{id}/versions/{version}")
    @RequiresPermission(WorkflowPermissions.DEFINITION_READ)
    public VersionResponse getVersion(@PathVariable String id, @PathVariable int version) {
        return VersionResponse.of(definitionQueries.version(uuid(id), version));
    }

    /** Walks the draft over a sample payload; it calls nothing out and writes nothing. */
    @Operation(
            summary = "Simulate a draft",
            description =
                    "Walks the draft over a sample `input` and canned step outputs (`steps`) and reports the path, the "
                    + "decisions and the variables. It calls nothing out and writes nothing. Requires "
                    + "`workflow:definition:read`.")
    @PostMapping("/definitions/{id}/simulate")
    @RequiresPermission(WorkflowPermissions.DEFINITION_READ)
    public SimulationResponse simulateDefinition(
            @PathVariable String id, @RequestBody(required = false) SimulateRequest request) {
        SimulateDefinitionUseCase.Result result = simulate.execute(new SimulateDefinitionUseCase.Command(
                uuid(id),
                request == null ? Map.of() : request.input(),
                request == null ? Map.of() : request.steps()));
        return new SimulationResponse(
                result.path(),
                result.decisions().stream()
                        .map(decision -> new DecisionResponse(decision.node(), decision.caseIndex(), decision.next()))
                        .toList(),
                result.variables(),
                result.stoppedAt(),
                result.problems().stream().map(ProblemResponse::of).toList());
    }

    private static UUID uuid(String id) {
        return UUID.fromString(id);
    }

    /** A conditional change without its version would silently overwrite another caller's, so it answers 428. */
    private static ExpectedVersion required(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException(
                    "If-Match is required: send the version this change is based on.");
        }
        return ExpectedVersion.fromIfMatch(ifMatch);
    }
}
