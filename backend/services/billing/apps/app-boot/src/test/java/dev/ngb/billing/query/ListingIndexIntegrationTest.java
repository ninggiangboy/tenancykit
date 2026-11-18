package dev.ngb.billing.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.Application;
import dev.ngb.billing.coupon.adapter.coupon.persistence.JooqCouponReadRepository;
import dev.ngb.billing.coupon.adapter.coupon.persistence.JooqPromotionCodeReadRepository;
import dev.ngb.billing.coupon.adapter.redemption.persistence.JooqRedemptionReadRepository;
import dev.ngb.billing.customer.adapter.customer.persistence.JooqCustomerReadRepository;
import dev.ngb.billing.invoicing.adapter.invoice.persistence.JooqInvoiceReadRepository;
import dev.ngb.billing.invoicing.adapter.subscription.persistence.JooqSubscriptionReadRepository;
import dev.ngb.billing.money.adapter.charge.persistence.JooqChargeReadRepository;
import dev.ngb.billing.money.adapter.ledger.persistence.JooqLedgerReadRepository;
import dev.ngb.billing.money.adapter.reservation.persistence.JooqReservationReadRepository;
import dev.ngb.billing.money.adapter.wallet.persistence.JooqWalletReadRepository;
import dev.ngb.billing.payment.adapter.incident.persistence.JooqIncidentReadRepository;
import dev.ngb.billing.payment.adapter.payment.persistence.JooqPaymentReadRepository;
import dev.ngb.billing.payment.adapter.refund.persistence.JooqRefundReadRepository;
import dev.ngb.billing.payment.adapter.stripe.persistence.JooqStripeEventReadRepository;
import dev.ngb.billing.pricing.adapter.plan.persistence.JooqPlanReadRepository;
import dev.ngb.billing.support.PostgresContainerConfig;
import dev.ngb.billing.usage.adapter.usage.persistence.JooqUsageEventReadRepository;
import dev.ngb.billing.webhook.adapter.delivery.persistence.JooqWebhookDeliveryReadRepository;
import dev.ngb.billing.webhook.adapter.endpoint.persistence.JooqWebhookEndpointReadRepository;
import dev.ngb.billing_jooq.Tables;
import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.FieldType;
import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.core.spring.query.ListingIndexCoverage;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Every listing sorts only on fields that lead an index of their table, so a sort a caller asks for never scans the
 * whole table. Runs against the migrated schema.
 */
@SpringBootTest(classes = Application.class)
@Import(PostgresContainerConfig.class)
class ListingIndexIntegrationTest {

    @Autowired DSLContext dsl;

    @Test
    void everyListingIsCovered() {
        Map<String, ListBinding> listings = Map.ofEntries(
                Map.entry("customer", JooqCustomerReadRepository.listBinding()),
                Map.entry("wallet", JooqWalletReadRepository.listBinding()),
                Map.entry("charge", JooqChargeReadRepository.listBinding()),
                Map.entry("reservation", JooqReservationReadRepository.listBinding()),
                Map.entry("ledger", JooqLedgerReadRepository.listBinding()),
                Map.entry("payment", JooqPaymentReadRepository.listBinding()),
                Map.entry("invoice", JooqInvoiceReadRepository.listBinding()),
                Map.entry("refund", JooqRefundReadRepository.listBinding()),
                Map.entry("subscription", JooqSubscriptionReadRepository.listBinding()),
                Map.entry("usage", JooqUsageEventReadRepository.listBinding()),
                Map.entry("coupon", JooqCouponReadRepository.listBinding()),
                Map.entry("promotion code", JooqPromotionCodeReadRepository.listBinding()),
                Map.entry("redemption", JooqRedemptionReadRepository.listBinding()),
                Map.entry("delivery", JooqWebhookDeliveryReadRepository.listBinding()),
                Map.entry("endpoint", JooqWebhookEndpointReadRepository.listBinding()),
                Map.entry("stripe event", JooqStripeEventReadRepository.listBinding()),
                Map.entry("incident", JooqIncidentReadRepository.listBinding()),
                Map.entry("plan", JooqPlanReadRepository.listBinding()));

        listings.forEach((name, binding) ->
                assertThat(ListingIndexCoverage.missing(dsl, binding)).as("the %s listing", name).isEmpty());
    }

    @Test
    void aSortableFieldWithoutAnIndexIsReported() {
        FilterSchema schema = FilterSchema.of("id", "id",
                FieldSpec.of("id", FieldType.UUID).asSortable(),
                FieldSpec.of("currency", FieldType.STRING).asSortable());
        ListBinding binding = ListBinding.of(schema).column("id", Tables.INVOICES.ID).column("currency", Tables.INVOICES.CURRENCY).build();

        assertThat(ListingIndexCoverage.missing(dsl, binding)).singleElement().asString().contains("currency").contains("invoices");
    }
}
