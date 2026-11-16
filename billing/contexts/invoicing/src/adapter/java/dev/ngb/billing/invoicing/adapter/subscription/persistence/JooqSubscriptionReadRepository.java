package dev.ngb.billing.invoicing.adapter.subscription.persistence;

import static dev.ngb.billing_jooq.Tables.SUBSCRIPTIONS;
import static dev.ngb.billing_jooq.Tables.SUBSCRIPTION_EVENTS;
import static org.jooq.impl.DSL.noCondition;

import dev.ngb.billing.invoicing.application.subscription.query.SubscriptionHistoryEntryView;
import dev.ngb.billing.invoicing.application.subscription.query.SubscriptionListing;
import dev.ngb.billing.invoicing.application.subscription.query.SubscriptionReadRepository;
import dev.ngb.billing.invoicing.application.subscription.query.SubscriptionView;
import dev.ngb.billing.invoicing.domain.subscription.SubscriptionHistoryEntry;
import dev.ngb.billing.invoicing.domain.subscription.SubscriptionStatus;
import dev.ngb.billing.pricing.api.plan.BillingCycle;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing_jooq.tables.records.SubscriptionsRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/** Selects subscriptions and their history straight into views; no aggregate is built. */
public class JooqSubscriptionReadRepository implements SubscriptionReadRepository {

    /** The subscription fields a caller may filter and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(SubscriptionListing.SCHEMA)
            .column("id", SUBSCRIPTIONS.ID)
            .column("customer_id", SUBSCRIPTIONS.CUSTOMER_ID)
            .column("wallet_id", SUBSCRIPTIONS.WALLET_ID)
            .column("plan_id", SUBSCRIPTIONS.PLAN_ID)
            .column("plan_code", SUBSCRIPTIONS.PLAN_CODE)
            .column("cycle", SUBSCRIPTIONS.CYCLE)
            .column("status", SUBSCRIPTIONS.STATUS)
            .column("anchor", SUBSCRIPTIONS.ANCHOR)
            .column("periods_charged", SUBSCRIPTIONS.PERIODS_CHARGED)
            .column("cancel_at_period_end", SUBSCRIPTIONS.CANCEL_AT_PERIOD_END)
            .column("canceled_at", SUBSCRIPTIONS.CANCELED_AT)
            .column("next_renewal_at", SUBSCRIPTIONS.NEXT_RENEWAL_AT)
            .column("renewal_attempts", SUBSCRIPTIONS.RENEWAL_ATTEMPTS)
            .column("created_at", SUBSCRIPTIONS.CREATED_AT)
            .column("updated_at", SUBSCRIPTIONS.UPDATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the subscription listing, for the test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqSubscriptionReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<SubscriptionView> list(ListQuery query, List<UUID> customerIds) {
        if (customerIds != null && customerIds.isEmpty()) {
            return query.emptyPage();
        }
        Condition scope = customerIds == null ? noCondition() : SUBSCRIPTIONS.CUSTOMER_ID.in(customerIds);
        return JooqLists.fetch(dsl, SUBSCRIPTIONS, scope, BINDING, query, JooqSubscriptionReadRepository::toView);
    }

    @Override
    public Optional<SubscriptionView> findById(UUID id) {
        return dsl.selectFrom(SUBSCRIPTIONS)
                .where(SUBSCRIPTIONS.ID.eq(id))
                .fetchOptional(JooqSubscriptionReadRepository::toView);
    }

    @Override
    public List<SubscriptionHistoryEntryView> history(UUID subscriptionId) {
        return dsl.select(SUBSCRIPTION_EVENTS.ID, SUBSCRIPTION_EVENTS.TYPE, SUBSCRIPTION_EVENTS.PERIOD_NUMBER, SUBSCRIPTION_EVENTS.PERIOD_START,
                        SUBSCRIPTION_EVENTS.PERIOD_END, SUBSCRIPTION_EVENTS.AMOUNT, SUBSCRIPTIONS.CURRENCY, SUBSCRIPTION_EVENTS.PAID_AMOUNT,
                        SUBSCRIPTION_EVENTS.CHARGE_ID, SUBSCRIPTION_EVENTS.DETAIL, SUBSCRIPTION_EVENTS.OCCURRED_AT)
                .from(SUBSCRIPTION_EVENTS)
                .join(SUBSCRIPTIONS)
                .on(SUBSCRIPTIONS.ID.eq(SUBSCRIPTION_EVENTS.SUBSCRIPTION_ID).and(SUBSCRIPTIONS.TENANT_ID.eq(SUBSCRIPTION_EVENTS.TENANT_ID)))
                .where(SUBSCRIPTION_EVENTS.SUBSCRIPTION_ID.eq(subscriptionId))
                .orderBy(SUBSCRIPTION_EVENTS.OCCURRED_AT, SUBSCRIPTION_EVENTS.ID)
                .fetch(row -> new SubscriptionHistoryEntryView(
                        row.value1(),
                        SubscriptionHistoryEntry.Type.valueOf(row.value2()),
                        row.value3(),
                        row.value4(),
                        row.value5(),
                        row.value6() == null ? null : Money.of(row.value6(), Currency.of(row.value7().strip())),
                        row.value8() == null ? null : Money.of(row.value8(), Currency.of(row.value7().strip())),
                        row.value9(),
                        row.value10(),
                        row.value11()));
    }

    private static SubscriptionView toView(SubscriptionsRecord row) {
        BillingCycle cycle = BillingCycle.valueOf(row.getCycle());
        int periodsCharged = row.getPeriodsCharged();
        // Periods are counted from the anchor, so the current one is derived the same way the aggregate derives it.
        Instant start = periodsCharged == 0 ? null : cycle.plus(row.getAnchor(), periodsCharged - 1L);
        Instant end = periodsCharged == 0 ? null : cycle.plus(row.getAnchor(), periodsCharged);
        return new SubscriptionView(
                row.getId(),
                new CustomerId(row.getCustomerId()),
                new WalletId(row.getWalletId()),
                row.getPlanId(),
                row.getPlanCode(),
                cycle,
                SubscriptionStatus.valueOf(row.getStatus()),
                row.getAnchor(),
                periodsCharged,
                start,
                end,
                row.getCancelAtPeriodEnd(),
                row.getCanceledAt(),
                row.getNextRenewalAt(),
                row.getRenewalAttempts(),
                row.getCreatedAt());
    }
}
