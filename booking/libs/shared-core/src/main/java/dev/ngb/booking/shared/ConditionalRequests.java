package dev.ngb.booking.shared;

import dev.ngb.core.version.ExpectedVersion;

/** The request edge's conditional rules, in the shared core so every endpoint that compares a tag reads them alike. */
public final class ConditionalRequests {

    private ConditionalRequests() {}

    /**
     * The versions an {@code If-Match} accepts.
     *
     * @throws PreconditionRequiredException when the header is absent, because a booking document is never written
     *     blind: two editors saving the same draft would otherwise overwrite each other silently
     */
    public static ExpectedVersion requireIfMatch(String header) {
        if (header == null || header.isBlank()) {
            throw new PreconditionRequiredException();
        }
        return ExpectedVersion.fromIfMatch(header);
    }
}
