package dev.ngb.workflow.runtime.adapter.instance.web;

import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.runtime.application.runtime.query.InstanceQueries;
import dev.ngb.workflow.runtime.application.runtime.query.InstanceView;
import dev.ngb.workflow.shared.WorkflowPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a Tenant's flows did and are doing: the instances it started, their state and the timeline of each. No endpoint
 * returns the trigger input or a secret; the timeline carries redacted requests and responses only.
 */
@Tag(
        name = "Workflow instances",
        description =
                "What a tenant's flows did and are doing: the instances started and their state.")
@Profile("api")
@RestController
@RequestMapping("/api/v1")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
public class InstanceHistoryController {

    private final InstanceQueries instances;

    public InstanceHistoryController(InstanceQueries instances) {
        this.instances = instances;
    }

    @Operation(
            summary = "List workflow instances",
            description =
                    "Returns the instances the tenant's flows started, narrowed by `status`, `definition` code and "
                    + "`correlationKey`, paged with `limit` (default 50) and `offset`. No answer carries the trigger input "
                    + "or a secret.")
    @GetMapping("/instances")
    @RequiresPermission(WorkflowPermissions.INSTANCE_READ)
    public List<InstanceView> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "definition", required = false) String definitionCode,
            @RequestParam(value = "correlationKey", required = false) String correlationKey,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return instances.list(status, definitionCode, correlationKey, limit, offset);
    }

    @Operation(
            summary = "Get a workflow instance",
            description =
                    "Returns one instance with its state and, when it failed, the reason. An unknown id answers `404`. ")
    @GetMapping("/instances/{id}")
    @RequiresPermission(WorkflowPermissions.INSTANCE_READ)
    public ResponseEntity<InstanceView> get(@PathVariable String id) {
        InstanceView view = instances.get(UUID.fromString(id));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(view.rowVersion())).body(view);
    }
}
