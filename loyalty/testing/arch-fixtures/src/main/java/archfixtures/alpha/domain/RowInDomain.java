package archfixtures.alpha.domain;

import dev.ngb.loyalty_jooq.tables.records.EventInboxRecord;

/** Violates persistence placement: a generated jOOQ type outside adapter.persistence. */
public class RowInDomain {
    EventInboxRecord record;
}
