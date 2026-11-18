package dev.ngb.mailroom.audience.api.unsubscribe;

import dev.ngb.core.id.TenantId;

/**
 * The sending context's contract for a recipient's unsubscribe: an idempotent withdrawal of consent for marketing.
 * It writes a {@code MARKETING} suppression and flips the receiver; it never stops transactional mail.
 */
public interface UnsubscribeAddress {

    void unsubscribe(TenantId tenantId, String address);
}
