package dev.ngb.mailroom.content.api.render;

import java.util.UUID;

/**
 * The three parts a render produces. {@code templateId} is null and {@code revision} zero for an inline body a caller
 * supplied directly; the same sandbox limits apply either way.
 */
public record RenderedEmail(UUID templateId, int revision, String subject, String html, String text) {}
