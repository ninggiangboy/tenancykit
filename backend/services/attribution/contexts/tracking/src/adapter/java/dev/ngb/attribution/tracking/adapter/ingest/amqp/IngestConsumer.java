package dev.ngb.attribution.tracking.adapter.ingest.amqp;

import dev.ngb.attribution.tracking.api.ingest.ClickEvent;
import dev.ngb.attribution.tracking.application.ingest.command.IngestClicksUseCase;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.visitor.DeviceClass;
import dev.ngb.core.visitor.TrafficClass;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the payloads drained from the ingest queue into clicks.
 *
 * <p>One message is one click. An unparseable message is discarded and counted rather than redelivered, because it can
 * never become valid; a row that keeps failing is handed to the dead-letter exchange and counted. A stored click is
 * counted once, and a redelivery that writes nothing is counted as a duplicate, so the queue's at-least-once delivery
 * does not inflate a report.
 *
 * <p>The batch is processed as one list, so the use case can group its tenants and open one tenant-scoped transaction
 * per group; row-level security means that grouping is not an optimisation but the only correct shape.
 */
public class IngestConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(IngestConsumer.class);

    private final IngestClicksUseCase useCase;
    private final JsonMapper json;
    private final RabbitTemplate rabbit;
    private final String deadLetterExchange;
    private final Counter consumedCounter;
    private final Counter storedCounter;
    private final Counter duplicateCounter;
    private final Counter invalidCounter;
    private final Counter deadCounter;

    public IngestConsumer(
            IngestClicksUseCase useCase,
            JsonMapper json,
            RabbitTemplate rabbit,
            String deadLetterExchange,
            MeterRegistry meters) {
        this.useCase = useCase;
        this.json = json;
        this.rabbit = rabbit;
        this.deadLetterExchange = deadLetterExchange;
        this.consumedCounter = Counter.builder("attribution.ingest.consumed")
                .description("Messages read from the ingest queue")
                .register(meters);
        this.storedCounter = Counter.builder("attribution.ingest.stored")
                .description("Clicks written to storage")
                .register(meters);
        this.duplicateCounter = Counter.builder("attribution.ingest.duplicate")
                .description("Clicks already stored, from a redelivery")
                .register(meters);
        this.invalidCounter = Counter.builder("attribution.ingest.invalid")
                .description("Messages discarded because they did not parse as a click")
                .register(meters);
        this.deadCounter = Counter.builder("attribution.ingest.dead")
                .description("Clicks that could not be stored and were dead-lettered")
                .register(meters);
    }

    /**
     * Consume one broker batch.
     *
     * @param messages the batch the container delivered; the container acks after this returns normally
     */
    public void consume(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<ClickEvent> events = new ArrayList<>(messages.size());
        for (Message message : messages) {
            consumedCounter.increment();
            try {
                events.add(read(message));
            } catch (RuntimeException invalid) {
                // A payload that is not a click can never become one, so it is dropped rather than redelivered.
                invalidCounter.increment();
                LOG.warn("discarded an ingest message that did not parse: {}", invalid.getMessage());
            }
        }
        if (events.isEmpty()) {
            return;
        }
        IngestClicksUseCase.Result result = useCase.execute(events);
        storedCounter.increment(result.stored());
        duplicateCounter.increment(result.duplicates());
        if (result.deadCount() > 0) {
            deadCounter.increment(result.deadCount());
            deadLetter(result.dead());
        }
    }

    /** Read one payload exactly as the ingest schema describes it. */
    ClickEvent read(Message message) {
        Wire wire = json.readValue(message.getBody(), Wire.class);
        if (wire.event_id() == null || wire.tenant_id() == null || wire.click_id() == null || wire.link_id() == null
                || wire.occurred_at() == null || wire.device_class() == null || wire.traffic_class() == null
                || wire.visitor_hash() == null) {
            throw new IllegalArgumentException("the click is missing a required member");
        }
        return new ClickEvent(
                UUID.fromString(wire.event_id()),
                new TenantId(UUID.fromString(wire.tenant_id())),
                UUID.fromString(wire.click_id()),
                UUID.fromString(wire.link_id()),
                uuidOrNull(wire.destination_id()),
                uuidOrNull(wire.campaign_id()),
                Instant.parse(wire.occurred_at()),
                DeviceClass.valueOf(wire.device_class()),
                wire.browser_family(),
                wire.country(),
                wire.referrer_domain(),
                wire.entry(),
                TrafficClass.valueOf(wire.traffic_class()),
                wire.visitor_hash());
    }

    /**
     * Publish a dead click to the dead-letter exchange once, so an operator can inspect it. The row is not stored: the
     * point of isolation is that one bad row cannot block the healthy ones beside it.
     */
    private void deadLetter(List<ClickEvent> dead) {
        if (deadLetterExchange == null || deadLetterExchange.isBlank()) {
            return;
        }
        try {
            rabbit.invoke(operations -> {
                for (ClickEvent event : dead) {
                    operations.send(deadLetterExchange, "attribution.ingest", new Message(
                            json.writeValueAsBytes(event), deadLetterProperties(event)));
                }
                return null;
            });
        } catch (RuntimeException failure) {
            // A failing dead-letter publish must not fail the batch that already committed its healthy rows.
            LOG.warn("could not dead-letter {} click(s)", dead.size(), failure);
        }
    }

    private static org.springframework.amqp.core.MessageProperties deadLetterProperties(ClickEvent event) {
        org.springframework.amqp.core.MessageProperties properties = new org.springframework.amqp.core.MessageProperties();
        properties.setContentType(org.springframework.amqp.core.MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(event.eventId().toString());
        properties.setType(ClickPublisher.CLICK_ROUTING_KEY);
        return properties;
    }

    private static UUID uuidOrNull(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    /** The payload's members, as the ingest schema names them. A member the schema marks optional may be absent. */
    record Wire(
            String event_id,
            String tenant_id,
            String click_id,
            String link_id,
            String destination_id,
            String campaign_id,
            String occurred_at,
            String device_class,
            String browser_family,
            String country,
            String referrer_domain,
            String entry,
            String traffic_class,
            String visitor_hash) {}
}
