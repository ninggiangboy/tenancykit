package dev.ngb.billing.money.adapter.reservation.persistence;

import static dev.ngb.billing_jooq.Tables.RESERVATIONS;
import static dev.ngb.billing_jooq.Tables.WALLETS;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectOne;

import dev.ngb.billing.money.api.reservation.ReservationId;
import dev.ngb.billing.money.api.reservation.ReservationStatus;
import dev.ngb.billing.money.application.reservation.query.ReservationListing;
import dev.ngb.billing.money.application.reservation.query.ReservationReadRepository;
import dev.ngb.billing.money.application.reservation.query.ReservationView;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing_jooq.tables.records.ReservationsRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/** Selects a reservation straight into the view; the aggregate is never built. */
public class JooqReservationReadRepository implements ReservationReadRepository {

    /** The reservation fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(ReservationListing.SCHEMA)
            .column("id", RESERVATIONS.ID)
            .column("wallet_id", RESERVATIONS.WALLET_ID)
            .related("wallet.customer_id", WALLETS.CUSTOMER_ID,
                    condition -> exists(selectOne().from(WALLETS).where(WALLETS.ID.eq(RESERVATIONS.WALLET_ID)).and(condition)))
            .column("status", RESERVATIONS.STATUS)
            .column("amount", RESERVATIONS.AMOUNT)
            .column("captured_amount", RESERVATIONS.CAPTURED_AMOUNT)
            .column("expires_at", RESERVATIONS.EXPIRES_AT)
            .column("reference", RESERVATIONS.REFERENCE)
            .column("created_at", RESERVATIONS.CREATED_AT)
            .column("closed_at", RESERVATIONS.CLOSED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the reservation listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqReservationReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<ReservationView> findById(ReservationId id) {
        return dsl.selectFrom(RESERVATIONS)
                .where(RESERVATIONS.ID.eq(id.value()))
                .fetchOptional(JooqReservationReadRepository::toView);
    }

    @Override
    public Page<ReservationView> list(ListQuery query, List<UUID> walletIds) {
        if (walletIds != null && walletIds.isEmpty()) {
            return query.emptyPage();
        }
        Condition scope = walletIds == null ? noCondition() : RESERVATIONS.WALLET_ID.in(walletIds);
        return JooqLists.fetch(dsl, RESERVATIONS, scope, BINDING, query, JooqReservationReadRepository::toView);
    }

    private static ReservationView toView(ReservationsRecord row) {
        Currency money = Currency.of(row.getCurrency().strip());
        return new ReservationView(
                new ReservationId(row.getId()),
                new WalletId(row.getWalletId()),
                ReservationStatus.valueOf(row.getStatus()),
                Money.of(row.getAmount(), money),
                Money.of(row.getCapturedAmount(), money),
                row.getExpiresAt(),
                row.getReference(),
                row.getCreatedAt(),
                row.getClosedAt());
    }
}
