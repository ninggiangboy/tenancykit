package dev.ngb.billing.money.adapter.wallet.persistence;

import static dev.ngb.billing_jooq.Tables.WALLETS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.billing.money.api.wallet.WalletMode;
import dev.ngb.billing.money.application.wallet.query.WalletListing;
import dev.ngb.billing.money.application.wallet.query.WalletReadRepository;
import dev.ngb.billing.money.application.wallet.query.WalletView;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing_jooq.tables.records.WalletsRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/** Selects wallets straight into the view; the aggregate is never built. */
public class JooqWalletReadRepository implements WalletReadRepository {

    /** The wallet fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(WalletListing.SCHEMA)
            .column("id", WALLETS.ID)
            .column("customer_id", WALLETS.CUSTOMER_ID)
            .column("currency", WALLETS.CURRENCY)
            .column("mode", WALLETS.MODE)
            .column("balance", WALLETS.BALANCE)
            .column("held", WALLETS.HELD)
            .column("credit_limit", WALLETS.CREDIT_LIMIT)
            .column("last_sequence", WALLETS.LAST_SEQUENCE)
            .column("created_at", WALLETS.CREATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the wallet listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqWalletReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<WalletView> findById(WalletId id) {
        return dsl.selectFrom(WALLETS)
                .where(WALLETS.ID.eq(id.value()))
                .fetchOptional(JooqWalletReadRepository::toView);
    }

    @Override
    public Page<WalletView> list(ListQuery query, List<UUID> customerIds) {
        if (customerIds != null && customerIds.isEmpty()) {
            return query.emptyPage();
        }
        Condition scope = customerIds == null ? noCondition() : WALLETS.CUSTOMER_ID.in(customerIds);
        return JooqLists.fetch(dsl, WALLETS, scope, BINDING, query, JooqWalletReadRepository::toView);
    }

    @Override
    public List<UUID> idsOf(List<UUID> customerIds) {
        if (customerIds.isEmpty()) {
            return List.of();
        }
        return dsl.select(WALLETS.ID)
                .from(WALLETS)
                .where(WALLETS.CUSTOMER_ID.in(customerIds))
                .fetch(WALLETS.ID);
    }

    private static WalletView toView(WalletsRecord row) {
        Currency money = Currency.of(row.getCurrency().strip());
        return new WalletView(
                new WalletId(row.getId()),
                new CustomerId(row.getCustomerId()),
                money,
                WalletMode.valueOf(row.getMode()),
                Money.of(row.getCreditLimit(), money),
                Money.of(row.getBalance(), money),
                Money.of(row.getHeld(), money),
                row.getLastSequence(),
                row.getCreatedAt());
    }
}
