package dev.ngb.mailroom.content.api.render;

import java.util.UUID;

/** A template's identity and current revision, enough for a campaign to pin what it will send. */
public record TemplateSummary(UUID id, int revision) {}
