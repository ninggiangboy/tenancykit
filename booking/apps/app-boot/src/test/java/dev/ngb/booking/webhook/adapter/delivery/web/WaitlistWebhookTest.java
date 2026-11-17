package dev.ngb.booking.webhook.adapter.delivery.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.ngb.booking.support.BookingApiTest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two waitlist facts' contract: the payload a receiver must accept.
 *
 * <p>Phase 11 wires the delivery path before the waitlist exists; phase 12 adds the use cases that emit these two and
 * replaces these example checks with their outbox payloads, the way {@code BookingEventContractTest} does for the
 * booking facts.
 */
class WaitlistWebhookTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void theOfferedExampleMatchesItsSchema() {
        assertExample("waitlist.offered");
    }

    @Test
    void theLapsedExampleMatchesItsSchema() {
        assertExample("waitlist.lapsed");
    }

    private static void assertExample(String type) {
        JsonNode schema = schema(type);
        assertThat(SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                        .getSchema(schema)
                        .validate(schema.get("examples").get(0)))
                .as("%s's example matches its committed schema", type)
                .isEmpty();
    }

    private static JsonNode schema(String type) {
        String resource = "/events/" + type + ".schema.json";
        try (InputStream stream = WaitlistWebhookTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(resource + " is not on the classpath");
            }
            return JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
