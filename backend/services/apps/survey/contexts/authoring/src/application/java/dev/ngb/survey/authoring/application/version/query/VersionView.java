package dev.ngb.survey.authoring.application.version.query;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One published version as the API returns it: the document and the number it was published under. */
public record VersionView(UUID id, int versionNo, Instant publishedAt, Map<String, Object> document) {}
