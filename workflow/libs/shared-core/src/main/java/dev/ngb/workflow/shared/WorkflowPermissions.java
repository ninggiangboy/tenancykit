package dev.ngb.workflow.shared;

import java.util.Set;

/** The workflow app's permission codes; a test asserts this set equals {@code contracts/permissions/workflow.yaml}. */
public final class WorkflowPermissions {

    public static final String DEFINITION_READ = "workflow:definition:read";
    public static final String DEFINITION_MANAGE = "workflow:definition:manage";
    public static final String INSTANCE_READ = "workflow:instance:read";
    public static final String INSTANCE_MANAGE = "workflow:instance:manage";
    public static final String TASK_READ = "workflow:task:read";
    public static final String TASK_COMPLETE = "workflow:task:complete";
    public static final String TASK_MANAGE = "workflow:task:manage";
    public static final String CONNECTION_MANAGE = "workflow:connection:manage";
    public static final String TRIGGER_MANAGE = "workflow:trigger:manage";
    public static final String WEBHOOK_MANAGE = "workflow:webhook:manage";

    public static final Set<String> CODES = Set.of(
            DEFINITION_READ,
            DEFINITION_MANAGE,
            INSTANCE_READ,
            INSTANCE_MANAGE,
            TASK_READ,
            TASK_COMPLETE,
            TASK_MANAGE,
            CONNECTION_MANAGE,
            TRIGGER_MANAGE,
            WEBHOOK_MANAGE);

    private WorkflowPermissions() {}
}
