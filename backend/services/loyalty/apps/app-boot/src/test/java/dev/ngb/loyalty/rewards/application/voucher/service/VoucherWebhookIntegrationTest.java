package dev.ngb.loyalty.rewards.application.voucher.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.loyalty.support.EventContracts;
import dev.ngb.loyalty.support.EventContracts.Delivery;
import dev.ngb.loyalty.support.RewardsApiTest;
import dev.ngb.loyalty.support.WebhookReceiver;
import dev.ngb.loyalty.webhook.application.delivery.command.DeliverWebhooksUseCase;
import dev.ngb.loyalty.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The three voucher facts loyalty announces: a redemption issued one, the point of sale used one, and a reversal voided
 * one.
 *
 * <p>Each is written to the outbox inside the transaction that produced it, relayed to the webhook module and delivered to
 * every endpoint that subscribed — once per endpoint, signed with that endpoint's own secret, and valid against the schema
 * in {@code contracts/events/loyalty}. A replayed use and a rolled-back transaction announce nothing, because nothing
 * happened.
 */
class VoucherWebhookIntegrationTest extends RewardsApiTest {

    @Autowired CreateEndpointUseCase endpoints;
    @Autowired DeliverWebhooksUseCase deliver;
    @Autowired OutboxRelay relay;

    @Test
    void aRewardRedemptionAnnouncesOneIssuedVoucherToEachSubscribedEndpoint() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, null, 7);
        try (WebhookReceiver receiver = new WebhookReceiver(tenantContext, endpoints, "/hook", "/second")) {
            receiver.subscribe(tenant, "/hook", VoucherEvents.VOUCHER_ISSUED);
            receiver.subscribe(tenant, "/second", VoucherEvents.VOUCHER_ISSUED);

            ApiResponse redeemed = redeemAndIssue(tenant, "M-1", "K-1", rewardId);
            deliverAll(tenant);

            assertThat(receiver.deliveries()).as("one delivery per subscribed endpoint").hasSize(2);
            Delivery delivery = receiver.only(VoucherEvents.VOUCHER_ISSUED);
            assertThat(receiver.deliveries()).extracting(Delivery::eventId).containsOnly(delivery.eventId());
            assertThat(receiver.deliveries()).allSatisfy(receiver::assertSigned);

            JsonNode data = delivery.data();
            JsonNode voucher = redeemed.json().get("voucher");
            assertThat(data.get("voucherId").asString())
                    .as("the id, not the code, is the key a Tenant derives its own keys from")
                    .isEqualTo(voucher.get("voucherId").asString());
            assertThat(data.get("code").asString()).isEqualTo(voucher.get("code").asString());
            assertThat(data.get("rewardId").asString()).isEqualTo(rewardId);
            assertThat(data.get("redemptionId").asString())
                    .isEqualTo(redeemed.json().get("redemptionId").asString());
            assertThat(data.get("externalRef").asString()).isEqualTo("M-1");
            assertThat(data.get("expiresAt").asString()).isEqualTo(voucher.get("expiresAt").asString());
            EventContracts.assertConforms(VoucherEvents.VOUCHER_ISSUED, data);

            assertThat(outboxRows(tenant, VoucherEvents.VOUCHER_ISSUED)).isEqualTo(1);
        }
    }

    @Test
    void aUseAnnouncesTheUsedVoucherAndAReplayAnnouncesNothing() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, null, 7);
        try (WebhookReceiver receiver = new WebhookReceiver(tenantContext, endpoints, "/hook", "/second")) {
            receiver.subscribe(tenant, "/hook", VoucherEvents.VOUCHER_USED);
            receiver.subscribe(tenant, "/second", VoucherEvents.VOUCHER_USED);
            String code = redeemAndIssue(tenant, "M-1", "K-1", rewardId)
                    .json()
                    .get("voucher")
                    .get("code")
                    .asString();

            ApiResponse used = useVoucher(tenant, code, "POS-1");
            deliverAll(tenant);

            assertThat(receiver.deliveries()).as("one delivery per subscribed endpoint").hasSize(2);
            Delivery delivery = receiver.only(VoucherEvents.VOUCHER_USED);
            assertThat(receiver.deliveries()).allSatisfy(receiver::assertSigned);
            JsonNode data = delivery.data();
            assertThat(data.get("voucherId").asString()).isEqualTo(used.json().get("voucherId").asString());
            assertThat(data.get("code").asString()).isEqualTo(code);
            assertThat(data.get("rewardId").asString()).isEqualTo(rewardId);
            assertThat(data.get("externalRef").asString()).isEqualTo("M-1");
            assertThat(data.get("useRef").asString()).isEqualTo("POS-1");
            assertThat(data.get("usedAt").asString()).isEqualTo(used.json().get("usedAt").asString());
            EventContracts.assertConforms(VoucherEvents.VOUCHER_USED, data);
            assertThat(outboxRows(tenant, VoucherEvents.VOUCHER_USED)).isEqualTo(1);

            // The same reference replays the use, and a refusal writes nothing either.
            assertThat(useVoucher(tenant, code, "POS-1").status()).isEqualTo(200);
            assertThat(useVoucher(tenant, code, "POS-2").status()).isEqualTo(409);
            deliverAll(tenant);

            assertThat(outboxRows(tenant, VoucherEvents.VOUCHER_USED)).as("a replay announces nothing").isEqualTo(1);
            assertThat(receiver.deliveries()).as("so nothing new is delivered").hasSize(2);
        }
    }

    @Test
    void aReversalAnnouncesTheVoidWithItsReason() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, 5L, 7);
        try (WebhookReceiver receiver = new WebhookReceiver(tenantContext, endpoints, "/hook", "/second")) {
            receiver.subscribe(tenant, "/hook", VoucherEvents.VOUCHER_VOIDED);
            receiver.subscribe(tenant, "/second", VoucherEvents.VOUCHER_VOIDED);
            ApiResponse redeemed = redeemAndIssue(tenant, "M-1", "K-1", rewardId);
            String code = redeemed.json().get("voucher").get("code").asString();
            UUID redemptionId = UUID.fromString(redeemed.json().get("redemptionId").asString());

            assertThat(reverseRedemption(tenant, "M-1", redemptionId).status()).isEqualTo(200);
            deliverAll(tenant);

            assertThat(receiver.deliveries()).as("one delivery per subscribed endpoint").hasSize(2);
            Delivery delivery = receiver.only(VoucherEvents.VOUCHER_VOIDED);
            assertThat(receiver.deliveries()).allSatisfy(receiver::assertSigned);
            JsonNode data = delivery.data();
            assertThat(data.get("voucherId").asString())
                    .isEqualTo(redeemed.json().get("voucher").get("voucherId").asString());
            assertThat(data.get("code").asString()).isEqualTo(code);
            assertThat(data.get("rewardId").asString()).isEqualTo(rewardId);
            assertThat(data.get("redemptionId").asString()).isEqualTo(redemptionId.toString());
            assertThat(data.get("externalRef").asString())
                    .as("a reversal void keeps the reference; only an erasure releases it")
                    .isEqualTo("M-1");
            assertThat(data.get("reason").asString()).isEqualTo("REVERSAL");
            EventContracts.assertConforms(VoucherEvents.VOUCHER_VOIDED, data);
            assertThat(outboxRows(tenant, VoucherEvents.VOUCHER_VOIDED)).isEqualTo(1);

            // A replayed reversal voids nothing a second time, so it announces nothing.
            assertThat(reverseRedemption(tenant, "M-1", redemptionId).status()).isEqualTo(200);
            deliverAll(tenant);
            assertThat(outboxRows(tenant, VoucherEvents.VOUCHER_VOIDED)).isEqualTo(1);
            assertThat(receiver.deliveries()).hasSize(2);
        }
    }

    /**
     * A refusal inside the reversal's hook unwinds the reversal, so neither the reversal's own event nor the void reaches
     * the outbox: the two facts are written in one transaction or not at all.
     */
    @Test
    void aRefusedReversalAnnouncesNoVoid() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, 5L, 7);
        try (WebhookReceiver receiver = new WebhookReceiver(tenantContext, endpoints, "/hook")) {
            receiver.subscribe(tenant, "/hook", VoucherEvents.VOUCHER_VOIDED);
            ApiResponse redeemed = redeemAndIssue(tenant, "M-1", "K-1", rewardId);
            String code = redeemed.json().get("voucher").get("code").asString();
            UUID redemptionId = UUID.fromString(redeemed.json().get("redemptionId").asString());
            assertThat(useVoucher(tenant, code, "POS-1").status()).isEqualTo(200);

            ApiResponse refused = reverseRedemption(tenant, "M-1", redemptionId);
            deliverAll(tenant);

            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.code()).isEqualTo("VOUCHER_ALREADY_USED");
            assertThat(outboxRows(tenant, VoucherEvents.VOUCHER_VOIDED)).isZero();
            assertThat(scalar(tenant, "select count(*) from outbox where type = 'points.redemption-reversed'"))
                    .as("the reversal it refused announced nothing either")
                    .isZero();
            assertThat(receiver.deliveries()).isEmpty();
        }
    }

    /**
     * A request whose transaction fails leaves no outbox row behind, so the relay and the delivery worker have nothing to
     * send. The same calls outside the rollback do announce, which is what makes the silence the rollback's.
     */
    @Test
    void aRolledBackRedemptionOrUseAnnouncesNothing() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, null, 7);
        try (WebhookReceiver receiver = new WebhookReceiver(tenantContext, endpoints, "/hook")) {
            receiver.subscribe(tenant, "/hook", VoucherEvents.VOUCHER_ISSUED, VoucherEvents.VOUCHER_USED);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> inTenant(tenant, () -> unitOfWork.transactional(() -> {
                assertThat(redeemReward(tenant, "M-1", "K-1", rewardId).status()).isEqualTo(201);
                throw new IllegalStateException("the redemption could not be committed");
            }))).isInstanceOf(IllegalStateException.class);

            assertThat(outboxRows(tenant, VoucherEvents.VOUCHER_ISSUED))
                    .as("the rolled-back redemption wrote no issued event")
                    .isZero();
            deliverAll(tenant);
            assertThat(receiver.deliveries()).isEmpty();

            String code = redeemAndIssue(tenant, "M-1", "K-1", rewardId).json().get("voucher").get("code").asString();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> inTenant(tenant, () -> unitOfWork.transactional(() -> {
                assertThat(useVoucher(tenant, code, "POS-1").status()).isEqualTo(200);
                throw new IllegalStateException("the use could not be committed");
            }))).isInstanceOf(IllegalStateException.class);

            assertThat(outboxRows(tenant, VoucherEvents.VOUCHER_USED)).isZero();
            assertThat(getVoucher(tenant, code).json().get("state").asString())
                    .as("the use rolled back with its event")
                    .isEqualTo("ISSUED");
            deliverAll(tenant);
            assertThat(receiver.deliveries()).extracting(Delivery::eventType)
                    .as("only the redemption that committed announced")
                    .containsExactly(VoucherEvents.VOUCHER_ISSUED);

            // The same use outside the rollback does announce it.
            assertThat(useVoucher(tenant, code, "POS-1").status()).isEqualTo(200);
            deliverAll(tenant);
            assertThat(receiver.deliveries()).extracting(Delivery::eventType)
                    .containsExactlyInAnyOrder(VoucherEvents.VOUCHER_ISSUED, VoucherEvents.VOUCHER_USED);
        }
    }

    private void deliverAll(TenantId tenant) {
        inTenant(tenant, () -> relay.relayDue());
        inTenant(tenant, () -> deliver.runOnce());
    }

    /** No endpoint subscribes to a type the catalog does not hold, which is what makes a subscription a guard. */
    @Test
    void theThreeVoucherTypesAreInTheCatalog() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 10L);
        for (String type : List.of(
                VoucherEvents.VOUCHER_ISSUED, VoucherEvents.VOUCHER_USED, VoucherEvents.VOUCHER_VOIDED)) {
            try (WebhookReceiver receiver = new WebhookReceiver(tenantContext, endpoints, "/hook")) {
                receiver.subscribe(tenant, "/hook", type);
                assertThat(receiver.deliveries()).isEmpty();
            }
        }
        assertThat(vouchers(tenant)).isZero();
    }
}
