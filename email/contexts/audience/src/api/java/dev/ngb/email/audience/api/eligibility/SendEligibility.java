package dev.ngb.email.audience.api.eligibility;

import dev.ngb.core.id.TenantId;
import dev.ngb.email.shared.EmailKind;

/**
 * The only way the sending context asks whether an address may receive a kind of email.
 *
 * <p>An unsubscribe is consent withdrawal for marketing only; a transactional message such as a password reset still
 * goes out. Only an explicit {@code ALL} block stops everything.
 */
public interface SendEligibility {

    boolean allows(TenantId tenantId, String address, EmailKind kind);
}
