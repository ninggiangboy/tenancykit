package dev.ngb.booking.webhook.adapter.delivery.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.ngb.booking.support.BookingApiTest;
import dev.ngb.booking.webhook.application.endpoint.port.EventTypeCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The webhook event catalog is exactly the committed schemas, and every schema's example is a payload that schema
 * accepts.
 *
 * <p>Each produced type is covered by a test that runs the use case emitting it and validates its outbox payload:
 * {@code BookingEventContractTest} for the booking facts, {@code BookingSeriesTest} for the series facts, and
 * {@code WaitlistWebhookTest} for the two waitlist facts.
 */
class WebhookSchemaContractTest extends BookingApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The test that produces each type and validates its payload today. */
    private static final Map<String, String> PRODUCERS = Map.ofEntries(
            Map.entry("booking.held", "dev.ngb.booking.booking.adapter.booking.web.BookingEventContractTest"),
            Map.entry("booking.confirmed", "dev.ngb.booking.booking.adapter.booking.web.BookingEventContractTest"),
            Map.entry("booking.cancelled", "dev.ngb.booking.booking.adapter.booking.web.BookingEventContractTest"),
            Map.entry("booking.expired", "dev.ngb.booking.booking.adapter.booking.web.BookingEventContractTest"),
            Map.entry("booking.rescheduled", "dev.ngb.booking.booking.adapter.booking.web.BookingEventContractTest"),
            Map.entry("booking.no-show", "dev.ngb.booking.booking.adapter.booking.web.BookingEventContractTest"),
            Map.entry("series.created", "dev.ngb.booking.booking.adapter.series.web.BookingSeriesTest"),
            Map.entry("series.cancelled", "dev.ngb.booking.booking.adapter.series.web.BookingSeriesTest"),
            Map.entry("waitlist.offered", "dev.ngb.booking.webhook.adapter.delivery.web.WaitlistWebhookTest"),
            Map.entry("waitlist.lapsed", "dev.ngb.booking.webhook.adapter.delivery.web.WaitlistWebhookTest"));

    @Autowired
    EventTypeCatalog catalog;

    @Test
    void theCatalogIsExactlyTheCommittedSchemas() {
        Set<String> schemas = new TreeSet<>(packaged().keySet());
        assertThat(schemas).as("the ten booking facts have a schema").hasSize(10);
        assertThat(catalog.eventTypes()).as("the endpoint catalog is the schemas themselves").isEqualTo(Set.copyOf(schemas));
    }

    @Test
    void everySchemaExampleIsAPayloadTheSchemaAccepts() {
        packaged().forEach((type, schema) -> assertThat(
                        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                                .getSchema(schema)
                                .validate(schema.get("examples").get(0)))
                .as("%s's committed example matches its own schema", type)
                .isEmpty());
    }

    @Test
    void everyProducedTypeNamesTheTestThatValidatesIt() {
        assertThat(PRODUCERS.keySet()).containsExactlyInAnyOrderElementsOf(catalog.eventTypes());
        PRODUCERS.forEach((type, test) -> assertThat(compiledTestExists(test))
                .as("%s names %s as the test that produces and validates it", type, test)
                .isTrue());
    }

    private static boolean compiledTestExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException notCompiled) {
            return false;
        }
    }

    private static Map<String, JsonNode> packaged() {
        String suffix = ".schema.json";
        Map<String, JsonNode> schemas = new java.util.TreeMap<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:events/*" + suffix)) {
                String name = resource.getFilename();
                if (name == null || !name.endsWith(suffix)) {
                    continue;
                }
                try (InputStream stream = resource.getInputStream()) {
                    schemas.put(
                            name.substring(0, name.length() - suffix.length()),
                            JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return schemas;
    }
}
