package archfixtures.customer.adapter.persistence;

import static dev.ngb.crm_jooq.Tables.EVENT_INBOX;

import org.jooq.DSLContext;
import org.jooq.Query;

/** Violates module-uses-only-own-tables: a customer adapter reads the identity module's table. */
public class UsesAnotherModulesTable {

    Query read(DSLContext dsl) {
        return dsl.selectFrom(EVENT_INBOX);
    }
}
