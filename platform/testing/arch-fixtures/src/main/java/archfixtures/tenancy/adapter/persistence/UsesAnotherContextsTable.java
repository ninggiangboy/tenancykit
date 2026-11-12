package archfixtures.tenancy.adapter.persistence;

import static dev.ngb.platform_jooq.Tables.OUTBOX;

import org.jooq.DSLContext;
import org.jooq.Query;

/** Violates context-uses-only-own-tables: a tenancy adapter reads the boot-owned outbox. */
public class UsesAnotherContextsTable {

    Query read(DSLContext dsl) {
        return dsl.selectFrom(OUTBOX);
    }
}
