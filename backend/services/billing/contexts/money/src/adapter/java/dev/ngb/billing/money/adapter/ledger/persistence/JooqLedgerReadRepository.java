package dev.ngb.billing.money.adapter.ledger.persistence;

import static dev.ngb.billing_jooq.Tables.LEDGER_ENTRIES;

import dev.ngb.billing.money.api.charge.ChargeId;
import dev.ngb.billing.money.api.ledger.EntryType;
import dev.ngb.billing.money.api.ledger.LedgerEntryId;
import dev.ngb.billing.money.api.reservation.ReservationId;
import dev.ngb.billing.money.application.ledger.query.LedgerEntryView;
import dev.ngb.billing.money.application.ledger.query.LedgerListing;
import dev.ngb.billing.money.application.ledger.query.LedgerReadRepository;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing_jooq.tables.records.LedgerEntriesRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import org.jooq.DSLContext;

/** Selects ledger entries straight into the view; the aggregate is never built. */
public class JooqLedgerReadRepository implements LedgerReadRepository {

    /** The ledger fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(LedgerListing.SCHEMA)
            .column("id", LEDGER_ENTRIES.ID)
            .column("seq", LEDGER_ENTRIES.SEQ)
            .column("type", LEDGER_ENTRIES.TYPE)
            .column("amount", LEDGER_ENTRIES.AMOUNT)
            .column("reference", LEDGER_ENTRIES.REFERENCE)
            .column("reservation_id", LEDGER_ENTRIES.RESERVATION_ID)
            .column("charge_id", LEDGER_ENTRIES.CHARGE_ID)
            .column("reversal_of", LEDGER_ENTRIES.REVERSAL_OF)
            .column("actor", LEDGER_ENTRIES.ACTOR)
            .column("reason", LEDGER_ENTRIES.REASON)
            .column("created_at", LEDGER_ENTRIES.CREATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the ledger listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqLedgerReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<LedgerEntryView> list(ListQuery query, WalletId walletId) {
        return JooqLists.fetch(dsl, LEDGER_ENTRIES, LEDGER_ENTRIES.WALLET_ID.eq(walletId.value()), BINDING, query,
                JooqLedgerReadRepository::toView);
    }

    private static LedgerEntryView toView(LedgerEntriesRecord row) {
        return new LedgerEntryView(
                new LedgerEntryId(row.getId()),
                row.getSeq(),
                EntryType.valueOf(row.getType()),
                Money.of(row.getAmount(), Currency.of(row.getCurrency().strip())),
                row.getReference(),
                row.getReservationId() == null ? null : new ReservationId(row.getReservationId()),
                row.getChargeId() == null ? null : new ChargeId(row.getChargeId()),
                row.getReversalOf() == null ? null : new LedgerEntryId(row.getReversalOf()),
                row.getActor(),
                row.getReason(),
                row.getCreatedAt());
    }
}
