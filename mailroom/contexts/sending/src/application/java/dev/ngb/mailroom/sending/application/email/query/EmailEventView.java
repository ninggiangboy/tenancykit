package dev.ngb.mailroom.sending.application.email.query;

import java.time.Instant;
import java.util.UUID;

/** One append-only fact about an email. */
public record EmailEventView(UUID id, String type, Integer linkId, Instant occurredAt) {}
