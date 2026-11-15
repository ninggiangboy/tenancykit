package dev.ngb.mailroom.content.application.template.query;

import java.util.UUID;

/**
 * One template as the API shows it: the pointer to the current revision and the optimistic-lock version a caller sends
 * back as {@code If-Match}. A read model, shaped for its caller; it never carries the revision bodies.
 */
public record TemplateView(UUID id, String key, String name, int currentRevision, long version) {}
