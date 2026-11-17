package dev.ngb.workflow.runtime.adapter.instance.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.runtime.application.runtime.query.InstanceQueries;
import dev.ngb.workflow.runtime.application.runtime.query.InstanceView;
import dev.ngb.workflow.shared.WorkflowPermissions;
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
@Profile("api")
@RestController
@RequestMapping("/api/v1")
public class InstanceHistoryController {

    private final InstanceQueries instances;

    public InstanceHistoryController(InstanceQueries instances) {
        this.instances = instances;
    }

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

    @GetMapping("/instances/{id}")
    @RequiresPermission(WorkflowPermissions.INSTANCE_READ)
    public ResponseEntity<InstanceView> get(@PathVariable String id) {
        InstanceView view = instances.get(UUID.fromString(id));
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(view.rowVersion())).body(view);
    }
}
