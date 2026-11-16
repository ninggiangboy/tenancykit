package dev.ngb.loyalty.support;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.webhook.WebhookSigner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The committed webhook contract, read as data: an event's payload is checked against the schema in
 * {@code contracts/events/loyalty}, and a delivery against the endpoint's own signing secret.
 *
 * <p>It is the same check the earn and redemption events are held to, kept in one place so a new event type is proved
 * against the file the Tenant reads rather than against the shape the code happens to produce.
 */
public final class EventContracts {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Path CONTRACTS = RepositoryPaths.repositoryRoot().resolve("contracts/events/loyalty");

    private EventContracts() {}

    /**
     * Checks the payload against the event's schema: every required field present, no field the contract does not name,
     * and each declared type, format, bound and enum respected.
     */
    public static void assertConforms(String eventType, JsonNode data) {
        JsonNode schema = read(eventType);
        assertThat(schema.get("additionalProperties").asBoolean()).as("the contract closes the payload").isFalse();
        for (JsonNode required : schema.get("required")) {
            assertThat(data.has(required.asString()))
                    .as(eventType + " carries the required " + required.asString())
                    .isTrue();
        }
        JsonNode properties = schema.get("properties");
        int present = 0;
        for (String name : properties.propertyNames()) {
            JsonNode value = data.get(name);
            if (value == null) {
                continue;
            }
            present++;
            assertConformsToField(eventType, name, value, properties.get(name));
        }
        assertThat(data.size()).as(eventType + " carries only the fields its contract names").isEqualTo(present);
    }

    /** Proves a delivery was signed with the secret of the endpoint it arrived at, and that it names its own event. */
    public static void assertSigned(String secret, Delivery delivery) {
        assertThat(secret).as("the endpoint " + delivery.path() + " was subscribed").isNotNull();
        assertThat(new WebhookSigner()
                        .sign(secret, Long.parseLong(delivery.timestamp()), delivery.body().getBytes(StandardCharsets.UTF_8)))
                .as("the delivery verifies with the endpoint's own signing secret")
                .isEqualTo(delivery.signature());
        assertThat(delivery.eventId()).as("the delivery names its event").isNotNull();
        UUID.fromString(delivery.eventId());
        assertThat(JSON.readTree(delivery.body()).get("type").asString()).isEqualTo(delivery.eventType());
    }

    /** One delivery as a receiver saw it: where it arrived, what the headers said and the envelope it carried. */
    public record Delivery(
            String path, String eventId, String eventType, String signature, String timestamp, String body) {

        public JsonNode data() {
            return JSON.readTree(body).get("data");
        }
    }

    private static JsonNode read(String eventType) {
        try {
            return JSON.readTree(Files.readString(CONTRACTS.resolve(eventType + ".schema.json"), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the schema of " + eventType, e);
        }
    }

    private static void assertConformsToField(String eventType, String name, JsonNode value, JsonNode spec) {
        String where = eventType + "." + name;
        String declared = spec.get("type").asString();
        switch (declared) {
            case "string" -> assertString(where, value, spec);
            case "integer" -> assertInteger(where, value, spec);
            case "array" -> assertArray(where, value, spec);
            default -> throw new IllegalStateException(where + " has a type this check does not read: " + declared);
        }
    }

    private static void assertString(String where, JsonNode value, JsonNode spec) {
        assertThat(value.isString()).as(where + " is a string").isTrue();
        String text = value.asString();
        if (spec.has("minLength")) {
            assertThat(text.length()).as(where + " is at least " + spec.get("minLength").asInt())
                    .isGreaterThanOrEqualTo(spec.get("minLength").asInt());
        }
        if (spec.has("maxLength")) {
            assertThat(text.length()).as(where + " is at most " + spec.get("maxLength").asInt())
                    .isLessThanOrEqualTo(spec.get("maxLength").asInt());
        }
        if (spec.has("enum")) {
            Set<String> allowed = new LinkedHashSet<>();
            for (JsonNode option : spec.get("enum")) {
                allowed.add(option.asString());
            }
            assertThat(allowed).as(where + " is one of the values its contract lists").contains(text);
        }
        if (spec.has("format")) {
            switch (spec.get("format").asString()) {
                case "uuid" -> UUID.fromString(text);
                case "date-time" -> java.time.Instant.parse(text);
                default -> throw new IllegalStateException(where + " has a format this check does not read");
            }
        }
    }

    private static void assertInteger(String where, JsonNode value, JsonNode spec) {
        assertThat(value.isIntegralNumber()).as(where + " is an integer").isTrue();
        if (spec.has("minimum")) {
            assertThat(value.asLong()).as(where + " is at least " + spec.get("minimum").asLong())
                    .isGreaterThanOrEqualTo(spec.get("minimum").asLong());
        }
    }

    /** An array of objects: its bounds, and every item against the item schema, which is checked the same way. */
    private static void assertArray(String where, JsonNode value, JsonNode spec) {
        assertThat(value.isArray()).as(where + " is an array").isTrue();
        if (spec.has("minItems")) {
            assertThat(value.size()).as(where + " holds at least " + spec.get("minItems").asInt())
                    .isGreaterThanOrEqualTo(spec.get("minItems").asInt());
        }
        JsonNode item = spec.get("items");
        for (JsonNode entry : value) {
            assertThat(entry.isObject()).as(where + " holds objects").isTrue();
            for (JsonNode required : item.get("required")) {
                assertThat(entry.has(required.asString()))
                        .as(where + " item carries the required " + required.asString())
                        .isTrue();
            }
            JsonNode properties = item.get("properties");
            int present = 0;
            for (String name : properties.propertyNames()) {
                JsonNode field = entry.get(name);
                if (field == null) {
                    continue;
                }
                present++;
                assertConformsToField(where + "[]", name, field, properties.get(name));
            }
            assertThat(entry.size()).as(where + " item carries only the fields its contract names").isEqualTo(present);
        }
    }
}
