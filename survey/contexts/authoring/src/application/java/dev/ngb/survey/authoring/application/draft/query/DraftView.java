package dev.ngb.survey.authoring.application.draft.query;

import java.time.Instant;
import java.util.Map;

/** The draft a builder reads, with the tag its next write has to carry. */
public record DraftView(Map<String, Object> document, long etag, Instant updatedAt) {}
