package dev.ngb.email.sending.domain.campaign;

/** Where one snapshot row stands. A row is claimed exactly once and ends {@code PUBLISHED} or {@code SKIPPED}. */
public enum RecipientState {
    PENDING,
    PUBLISHED,
    SKIPPED
}
