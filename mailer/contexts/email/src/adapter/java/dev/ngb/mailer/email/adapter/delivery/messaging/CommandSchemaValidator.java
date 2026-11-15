package dev.ngb.mailer.email.adapter.delivery.messaging;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.ngb.core.event.EventEnvelope;
import dev.ngb.mailer.email.application.email.command.AcceptEmailCommandUseCase;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates the {@code data} member of an {@code email.send} against the contract every producer reads.
 *
 * <p>The schema is loaded once, in the constructor, and a missing resource fails the context at startup rather than
 * every message at runtime: a packaging mistake (a Dockerfile that forgot {@code contracts/}) then shows up as a boot
 * failure instead of a silent dead-letter queue.
 */
public class CommandSchemaValidator {

    private static final String SCHEMA_RESOURCE = "/commands/email.send.schema.json";

    private final JsonMapper json;
    private final Schema schema;

    public CommandSchemaValidator(JsonMapper json) {
        this.json = json;
        String document = readSchema();
        this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(json.readTree(document));
    }

    public AcceptEmailCommandUseCase.Command validate(EventEnvelope event) {
        JsonNode data = json.valueToTree(event.payload());
        List<Error> errors = schema.validate(data);
        if (!errors.isEmpty()) {
            // The messages name a JSON pointer and a keyword, never the payload, so no recipient address is logged.
            throw new InvalidEmailCommandException("email.send failed its schema: " + errors.stream()
                    .map(error -> error.getInstanceLocation() + " " + error.getMessage())
                    .collect(Collectors.joining("; ")));
        }
        try {
            return new AcceptEmailCommandUseCase.Command(
                    required(data, "requestId"),
                    addresses(data, "to"),
                    data.has("cc") ? addresses(data, "cc") : List.of(),
                    required(data, "fromKey"),
                    optional(data, "fromName"),
                    optional(data, "replyTo"),
                    required(data, "subject"),
                    optional(data, "html"),
                    optional(data, "text"));
        } catch (IllegalArgumentException invalid) {
            // The schema protects the wire; the value objects protect every later caller. A string that passes the
            // pattern but still cannot be a header value is caught here, not inside a transport.
            throw new InvalidEmailCommandException(invalid.getMessage(), invalid);
        }
    }

    private String readSchema() {
        try (InputStream stream = CommandSchemaValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("the command schema " + SCHEMA_RESOURCE
                        + " is not on the classpath; the image must package contracts/commands");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read " + SCHEMA_RESOURCE, failure);
        }
    }

    private static String required(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asString();
    }

    private static String optional(JsonNode data, String field) {
        JsonNode value = data.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static List<String> addresses(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(field + " must be an array of addresses");
        }
        List<String> addresses = new ArrayList<>();
        for (JsonNode item : value) {
            addresses.add(item.asString());
        }
        return List.copyOf(addresses);
    }
}
