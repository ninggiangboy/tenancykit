package dev.ngb.loyalty.points.adapter.ledger.persistence;

import static dev.ngb.loyalty_jooq.Tables.LEDGER_ENTRIES;
import static dev.ngb.loyalty_jooq.Tables.ORDERS;
import static dev.ngb.loyalty_jooq.Tables.ORDER_RETURNS;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.loyalty.points.application.ledger.query.LedgerEntryView;
import dev.ngb.loyalty.points.application.ledger.query.LedgerListing;
import dev.ngb.loyalty.points.application.ledger.query.LedgerReadRepository;
import dev.ngb.loyalty.points.domain.ledger.LedgerKind;
import dev.ngb.loyalty_jooq.tables.records.LedgerEntriesRecord;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;

/** Selects one member's ledger, newest first, with the references that explain each entry resolved in two queries. */
public class JooqLedgerReadRepository implements LedgerReadRepository {

    /** The ledger fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(LedgerListing.SCHEMA)
            .column("id", LEDGER_ENTRIES.ID)
            .column("kind", LEDGER_ENTRIES.KIND)
            .column("points", LEDGER_ENTRIES.POINTS)
            .column("created_at", LEDGER_ENTRIES.CREATED_AT)
            .build();

    private final DSLContext dsl;

    public JooqLedgerReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<LedgerEntryView> page(UUID memberId, ListQuery query) {
        return JooqLists.fetchPage(
                dsl,
                LEDGER_ENTRIES,
                LEDGER_ENTRIES.MEMBER_ID.eq(memberId),
                BINDING,
                query,
                rows -> {
                    Map<UUID, String> orderRefs = orderRefs(rows.stream()
                            .map(LedgerEntriesRecord::getOrderId)
                            .filter(java.util.Objects::nonNull)
                            .collect(Collectors.toSet()));
                    Map<UUID, String> returnRefs = returnRefs(rows.stream()
                            .map(LedgerEntriesRecord::getReturnId)
                            .filter(java.util.Objects::nonNull)
                            .collect(Collectors.toSet()));
                    return rows.stream()
                            .map(row -> new LedgerEntryView(
                                    row.getId(),
                                    LedgerKind.valueOf(row.getKind()).wireName(),
                                    row.getPoints(),
                                    row.getOrderId() == null ? null : orderRefs.get(row.getOrderId()),
                                    row.getReturnId() == null ? null : returnRefs.get(row.getReturnId()),
                                    row.getRedemptionId(),
                                    row.getCreatedAt()))
                            .toList();
                });
    }

    private Map<UUID, String> orderRefs(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return dsl.select(ORDERS.ID, ORDERS.ORDER_REF)
                .from(ORDERS)
                .where(ORDERS.ID.in(ids))
                .fetchMap(ORDERS.ID, ORDERS.ORDER_REF);
    }

    private Map<UUID, String> returnRefs(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return dsl.select(ORDER_RETURNS.ID, ORDER_RETURNS.RETURN_REF)
                .from(ORDER_RETURNS)
                .where(ORDER_RETURNS.ID.in(ids))
                .fetchMap(ORDER_RETURNS.ID, ORDER_RETURNS.RETURN_REF);
    }
}
