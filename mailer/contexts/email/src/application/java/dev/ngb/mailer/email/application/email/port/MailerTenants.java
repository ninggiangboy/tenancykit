package dev.ngb.mailer.email.application.email.port;

import dev.ngb.core.id.TenantId;
import java.util.List;

/**
 * The tenants this service has work for. It exists because a scheduled job has no request scope and mailer keeps no
 * replica of Platform's tenant table to get one from: a job can only act inside one tenant's scope at a time, so
 * something has to say which tenants there are.
 *
 * <p>The list is ops-maintained and deliberately not row-level secured — the sweep has to read it before it can
 * assume any tenant's scope. It holds no personal data and no delivery content.
 */
public interface MailerTenants {

    /** Every tenant a job should visit, read in the Platform scope because no tenant scope exists yet. */
    List<TenantId> activeIds();
}
