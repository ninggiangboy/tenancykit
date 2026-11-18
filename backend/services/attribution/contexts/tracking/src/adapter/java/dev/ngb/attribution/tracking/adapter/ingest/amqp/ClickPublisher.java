package dev.ngb.attribution.tracking.adapter.ingest.amqp;

import dev.ngb.attribution.tracking.api.ingest.ClickEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends a drained batch of clicks to the ingest exchange and waits for the broker's confirms before saying it left.
 *
 * <p>The body is the click payload itself, not the suite's domain-event envelope: an ingest batch is not a domain event
 * and the worker's consumer reads the payload directly. Each message carries {@code type=click}, which is the routing
 * key the ingest queue binds, so a later payload type is one more binding entry and not a new queue.
 *
 * <p>When the publish fails the batch is counted rather than retried in place: the redirect already answered {@code
 * 302}, so there is nothing to fail, and the buffer's depth gauge is the signal to act on. A healthy broker stores
 * every offered event exactly once, which is what the load test asserts.
 */
public class ClickPublisher {

    /** The routing key the ingest queue binds for a click; a new payload type is one more entry in that list. */
    public static final String CLICK_ROUTING_KEY = "click";

    private static final long CONFIRM_TIMEOUT_MILLIS = 5_000;

    private final RabbitTemplate rabbit;
    private final JsonMapper json;
    private final String exchange;
    private final Counter publishedCounter;
    private final Counter failedCounter;

    public ClickPublisher(RabbitTemplate rabbit, JsonMapper json, String exchange, MeterRegistry meters) {
        this.rabbit = rabbit;
        this.json = json;
        this.exchange = exchange;
        this.publishedCounter = Counter.builder("attribution.ingest.published")
                .description("Clicks published to the ingest exchange")
                .register(meters);
        this.failedCounter = Counter.builder("attribution.ingest.publish.failed")
                .description("Clicks whose publish failed and were lost to the broker")
                .register(meters);
    }

    /**
     * @param batch the events drained from the buffer
     * @return how many the broker confirmed, so a caller can compare offered against stored
     */
    public int publish(List<ClickEvent> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            rabbit.invoke(operations -> {
                for (ClickEvent event : batch) {
                    operations.send(exchange, CLICK_ROUTING_KEY, message(event), null);
                }
                operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MILLIS);
                return null;
            });
            publishedCounter.increment(batch.size());
            return batch.size();
        } catch (RuntimeException failure) {
            // The redirect has already answered; the loss is counted and the buffer's depth shows a broker that is down.
            failedCounter.increment(batch.size());
            return 0;
        }
    }

    private Message message(ClickEvent event) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(event.eventId().toString());
        properties.setType(CLICK_ROUTING_KEY);
        properties.setHeader("tenant-id", event.tenantId().value().toString());
        return new Message(json.writeValueAsBytes(wire(event)), properties);
    }

    /**
     * The payload exactly as {@code contracts/attribution/ingest/click.schema.json} describes it. An absent optional
     * member is left out rather than written as null, so a consumer sees a missing value and not an empty one.
     */
    Map<String, Object> wire(ClickEvent event) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("event_id", event.eventId().toString());
        wire.put("tenant_id", event.tenantId().value().toString());
        wire.put("click_id", event.clickId().toString());
        wire.put("link_id", event.linkId().toString());
        putIfPresent(wire, "destination_id", event.destinationId());
        putIfPresent(wire, "campaign_id", event.campaignId());
        wire.put("occurred_at", event.occurredAt().toString());
        wire.put("device_class", event.deviceClass().name());
        wire.put("browser_family", event.browserFamily() == null ? "Unknown" : event.browserFamily());
        putIfPresent(wire, "country", event.country());
        putIfPresent(wire, "referrer_domain", event.referrerDomain());
        putIfPresent(wire, "entry", event.entry());
        wire.put("traffic_class", event.trafficClass().name());
        wire.put("visitor_hash", event.visitorHash());
        return wire;
    }

    private static void putIfPresent(Map<String, Object> target, String name, Object value) {
        if (value != null && !value.toString().isBlank()) {
            target.put(name, value.toString());
        }
    }
}
