package dev.ngb.loyalty.points.application.ledger.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.UUID;

/** One member's ledger, newest first, keyset-paginated. */
public interface LedgerReadRepository {

    Page<LedgerEntryView> page(UUID memberId, ListQuery query);
}
