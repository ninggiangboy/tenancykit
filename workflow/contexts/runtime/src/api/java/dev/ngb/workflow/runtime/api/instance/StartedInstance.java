package dev.ngb.workflow.runtime.api.instance;

import java.util.UUID;

/** The instance a start request produced, and the status it began with. */
public record StartedInstance(UUID instanceId, String status) {}
