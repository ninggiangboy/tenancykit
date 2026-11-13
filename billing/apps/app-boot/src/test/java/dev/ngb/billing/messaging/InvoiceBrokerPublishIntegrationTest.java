package dev.ngb.billing.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.Application;
import dev.ngb.billing.invoicing.application.cycle.command.CloseBillingCycleUseCase;
import dev.ngb.billing.money.MoneyTestWorld;
import dev.ngb.billing.money.MoneyTestWorld.World;
import dev.ngb.billing.money.api.charge.ChargeCommand;
import dev.ngb.billing.money.api.charge.ChargeWallet;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.support.OwnerDatabase;
import dev.ngb.billing.support.PostgresContainerConfig;
import dev.ngb.billing.support.ScopeTestSupport;
import dev.ngb.billing.webhook.adapter.delivery.json.JacksonEventBodyWriter;
import dev.ngb.core.event.EventEnvelope;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.broker.BrokerRelay;
import dev.ngb.core.messaging.broker.EnvelopeCodec;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Binding.DestinationType;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Issuing an invoice reaches the broker as one message with the documented envelope. */
@SpringBootTest(classes = Application.class)
@Import(PostgresContainerConfig.class)
class InvoiceBrokerPublishIntegrationTest {

    @Autowired MoneyTestWorld worlds;
    @Autowired ScopeTestSupport scopes;
    @Autowired OwnerDatabase owner;
    @Autowired ChargeWallet charge;
    @Autowired CloseBillingCycleUseCase closeCycle;
    @Autowired OutboxRelay relay;
    @Autowired BrokerRelay brokerRelay;
    @Autowired ConnectionFactory connectionFactory;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void issuingAnInvoicePublishesItToTheBillingEventsExchange() {
        String queue = "billing-test-" + UUID.randomUUID();
        AmqpAdmin admin = new RabbitAdmin(connectionFactory);
        RabbitTemplate rabbit = new RabbitTemplate(connectionFactory);
        admin.declareQueue(QueueBuilder.durable(queue).build());
        admin.declareBinding(new Binding(queue, DestinationType.QUEUE, "billing.events", "#", null));

        try {
            TenantId tenant = issueInvoice();
            scopes.runInTenant(tenant, brokerRelay::relayDue);

            Message invoiceMessage = receiveUntil(rabbit, queue, "invoice.created");
            assertThat(invoiceMessage).as("invoice.created reached billing.events").isNotNull();
            assertThat(invoiceMessage.getMessageProperties().getMessageId()).isNotBlank();
            Object tenantHeader = invoiceMessage.getMessageProperties().getHeader("tenant-id");
            assertThat(tenantHeader).isEqualTo(tenant.toString());

            EnvelopeCodec codec = new EnvelopeCodec(json);
            EventEnvelope event = codec.decode(invoiceMessage.getBody());
            assertThat(event.type()).isEqualTo("invoice.created");
            assertThat(event.tenantId()).isEqualTo(tenant);
            assertThat(event.payload()).containsKeys("invoiceId", "number", "total");
            assertThat(event.aggregate()).as("the aggregate survives the wire").isNotEqualTo(event.type());

            String body = new String(invoiceMessage.getBody(), StandardCharsets.UTF_8);
            JsonNode wire = json.readTree(body);
            assertThat(keys(wire)).containsExactlyInAnyOrder("id", "type", "aggregate", "tenantId", "occurredAt", "schemaVersion", "data");
            assertThat(wire.get("schemaVersion").asInt()).isEqualTo(2);
            assertThat(body).as("the webhook body is the same canonical document").isEqualTo(new JacksonEventBodyWriter(json).write(event));
        } finally {
            admin.deleteQueue(queue);
        }
    }

    /** A Postpaid charge whose period the cycle job closes, which issues the invoice. */
    private TenantId issueInvoice() {
        World postpaid = worlds.postpaid(1_000_000);
        TenantId tenant = postpaid.tenant().id();
        Money three = Money.of(300, postpaid.wallet().currency());
        scopes.inTenant(tenant, () -> charge.charge(new ChargeCommand(postpaid.wallet().id(), null, three, null, "work", UUID.randomUUID().toString())));
        scopes.inTenant(tenant, () -> charge.charge(
                new ChargeCommand(postpaid.wallet().id(), null, Money.of(700, postpaid.wallet().currency()), null, "more work", UUID.randomUUID().toString())));
        scopes.runInTenant(tenant, relay::relayDue);
        owner.jdbc().update(
                "update invoice_items set cycle_start = cycle_start - interval '1 month', cycle_end = cycle_end - interval '1 month' where tenant_id = ?",
                tenant.value());
        scopes.inTenant(tenant, closeCycle::runOnce);
        return tenant;
    }

    private Message receiveUntil(RabbitTemplate rabbit, String queue, String type) {
        EnvelopeCodec codec = new EnvelopeCodec(json);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        List<Message> others = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            Message message = rabbit.receive(queue, 1_000);
            if (message == null) {
                continue;
            }
            if (codec.decode(message.getBody()).type().equals(type)) {
                return message;
            }
            others.add(message);
        }
        return null;
    }

    private static Set<String> keys(JsonNode node) {
        return new java.util.LinkedHashSet<>(node.propertyNames());
    }
}
