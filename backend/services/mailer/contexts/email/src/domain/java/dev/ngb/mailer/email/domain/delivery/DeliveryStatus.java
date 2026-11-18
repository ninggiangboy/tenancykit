package dev.ngb.mailer.email.domain.delivery;

/**
 * Where one delivery stands. The set is closed on purpose: it is exactly what the ledger has to answer, and each
 * value maps to one branch of the delivery job.
 *
 * <ul>
 *   <li>{@code PENDING} — waiting for its next attempt, either the first one or after a transient failure.</li>
 *   <li>{@code IN_PROGRESS} — a worker holds it. A row left here by a crash becomes claimable again when its lease
 *       runs out, which is why the claim selects this status as well as {@code PENDING}.</li>
 *   <li>{@code SUCCEEDED} — the transport accepted the message. Under SES that means accepted, not delivered.</li>
 *   <li>{@code DEAD} — terminal: either the provider rejected the request, or the retry budget ran out.</li>
 * </ul>
 */
public enum DeliveryStatus {
    PENDING,
    IN_PROGRESS,
    SUCCEEDED,
    DEAD
}
