package dev.ngb.email.sending.domain.campaign;

/**
 * {@code COMPLETED} and {@code CANCELLED} are terminal. Pausing stops the worker taking more batches; it never
 * withdraws a command already published.
 */
public enum CampaignStatus {
    DRAFT,
    SCHEDULED,
    SENDING,
    PAUSED,
    COMPLETED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
