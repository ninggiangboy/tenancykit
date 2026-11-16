package dev.ngb.crm.customers.api.customer;

import java.time.Instant;
import java.util.UUID;

/**
 * One consent topic as the customer currently holds it: the status of the latest record of that topic, and when that
 * record happened.
 *
 * <p>A topic the customer never answered is absent from a profile, which is not the same answer as a revoked one.
 *
 * @param topicKey the topic the Tenant defined
 * @param status {@code GRANTED} or {@code REVOKED}
 * @param occurredAt when the record that decided the current status happened
 */
public record ConsentState(String topicKey, String status, Instant occurredAt) {}
