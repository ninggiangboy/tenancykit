package dev.ngb.mailroom.content.application.template.query;

import java.time.Instant;
import java.util.UUID;

/** One immutable revision as the API shows it. */
public record TemplateRevisionView(
        UUID id, int revision, String subject, String html, String text, Instant createdAt) {}
