package dev.ngb.mailer.email.adapter.email.persistence;

import static dev.ngb.mailer_jooq.Tables.MAILER_TENANTS;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.mailer.email.application.email.port.MailerTenants;
import java.util.List;
import org.jooq.DSLContext;

/**
 * The sweep's tenant list, plus its write side. The list opens the transaction itself so the Platform scope the sweep
 * binds is applied to this read: the sweep has no tenant scope yet, and this table is the one thing it must read
 * before it can create one. Registration is a plain insert in the caller's transaction, because accepting a command
 * already has one.
 */
public class JooqMailerTenants implements MailerTenants {

    private final UnitOfWork unitOfWork;
    private final DSLContext dsl;

    public JooqMailerTenants(UnitOfWork unitOfWork, DSLContext dsl) {
        this.unitOfWork = unitOfWork;
        this.dsl = dsl;
    }

    @Override
    public List<TenantId> activeIds() {
        return unitOfWork.transactional(() -> dsl.select(MAILER_TENANTS.TENANT_ID)
                .from(MAILER_TENANTS)
                .where(MAILER_TENANTS.ACTIVE.isTrue())
                .orderBy(MAILER_TENANTS.TENANT_ID)
                .fetch(record -> new TenantId(record.value1())));
    }

    @Override
    public void register(TenantId tenantId) {
        // The caller already holds a transaction (the accept use case), so this runs in it: accepting a command and
        // registering the tenant commit together or not at all.
        dsl.insertInto(MAILER_TENANTS)
                .set(MAILER_TENANTS.TENANT_ID, tenantId.value())
                .set(MAILER_TENANTS.ACTIVE, true)
                .onConflict(MAILER_TENANTS.TENANT_ID)
                .doNothing()
                .execute();
    }
}
