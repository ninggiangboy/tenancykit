package archfixtures.alpha.domain;

import dev.ngb.mailer_jooq.tables.records.EmailDeliveriesRecord;

/** Violates persistence placement: a generated jOOQ type outside adapter.persistence. */
public class RowInDomain {
    EmailDeliveriesRecord record;
}
