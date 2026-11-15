package dev.ngb.mailroom.sending.adapter.email.messaging;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.ngb.mailroom.sending.application.email.exception.EmailCommandRejectedException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates the {@code data} member of an {@code email.send} against the contract every producer reads.
 *
 * <p>The schema is loaded once, in the constructor; a missing resource fails the context at startup rather than every
 * message at runtime.
 */
public class EmailCommandSchemaValidator {

    private static final String SCHEMA_RESOURCE = "/commands/email.send.schema.json";

    private final JsonMapper json;
    private final Schema schema;

    public EmailCommandSchemaValidator(JsonMapper json) {
        this.json = json;
        this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(json.readTree(readSchema()));
    }

    /** @throws EmailCommandRejectedException with a fixed message, never the offending value */
    public void validate(Map<String, Object> data) {
        List<Error> errors = schema.validate(json.valueToTree(data));
        if (!errors.isEmpty()) {
            throw new EmailCommandRejectedException();
        }
    }

    private String readSchema() {
        try (InputStream stream = EmailCommandSchemaValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("the command schema " + SCHEMA_RESOURCE
                        + " is not on the classpath; the image must package contracts/commands");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read " + SCHEMA_RESOURCE, failure);
        }
    }
}
