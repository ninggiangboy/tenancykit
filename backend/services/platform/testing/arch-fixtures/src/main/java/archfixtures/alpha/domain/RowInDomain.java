package archfixtures.alpha.domain;

import dev.ngb.platform_jooq.tables.records.TenantsRecord;

/** Violates persistence placement: a generated jOOQ type outside adapter.persistence. */
public class RowInDomain {
    TenantsRecord record;
}
