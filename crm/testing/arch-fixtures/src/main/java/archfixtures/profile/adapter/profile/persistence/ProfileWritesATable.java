package archfixtures.profile.adapter.profile.persistence;

import org.jooq.DSLContext;

/** Violates profile-writes-nothing: the read-only profile context reaches a table. */
public class ProfileWritesATable {

    private final DSLContext dsl;

    public ProfileWritesATable(DSLContext dsl) {
        this.dsl = dsl;
    }

    public DSLContext dsl() {
        return dsl;
    }
}
