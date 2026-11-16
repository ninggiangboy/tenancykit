package dev.ngb.survey.authoring.adapter.document.schema;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import dev.ngb.survey.authoring.api.document.ValidationCode;
import dev.ngb.survey.authoring.api.document.ValidationError;
import dev.ngb.survey.authoring.application.draft.port.DocumentVerifier;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks a draft against the contract every evaluator reads: {@code contracts/survey/version.schema.json}.
 *
 * <p>The schema is loaded once, from the resources the build packages out of {@code contracts/survey/}, so a missing
 * contract fails at startup rather than surfacing as a document that silently validates. Both files are registered
 * under their {@code $id}, because the version schema references the expression schema by relative URI — a server that
 * did not resolve the reference would accept every expression unchallenged.
 *
 * <p>The schema states shape, not reasons. A failure maps into the contract's closed code list: a missing member is
 * {@code required}, an exceeded array, text or number bound is {@code limit_exceeded}, and every other shape failure —
 * an unknown question type, a member the version does not define, a value of the wrong kind — is
 * {@code unknown_type}.
 */
public class JsonSchemaDocumentVerifier implements DocumentVerifier {

    private static final String VERSION_URI = "https://suite.ngb.dev/survey/version.schema.json";
    private static final String EXPRESSION_URI = "https://suite.ngb.dev/survey/expression.schema.json";
    private static final String VERSION_RESOURCE = "/survey/version.schema.json";
    private static final String EXPRESSION_RESOURCE = "/survey/expression.schema.json";

    private final JsonMapper json;
    private final Schema schema;

    public JsonSchemaDocumentVerifier(JsonMapper json) {
        this.json = json;
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder
                        // `format: uuid`, `date` and `date-time` are assertions here: an id the server cannot use must
                        // fail the save rather than reach storage.
                        .schemaRegistryConfig(
                                SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
                        .schemas(Map.of(
                                VERSION_URI, read(VERSION_RESOURCE),
                                EXPRESSION_URI, read(EXPRESSION_RESOURCE))));
        this.schema = registry.getSchema(SchemaLocation.of(VERSION_URI));
    }

    @Override
    public List<ValidationError> structuralProblems(Map<String, Object> document) {
        List<Error> errors = schema.validate(json.valueToTree(document));
        List<ValidationError> problems = new ArrayList<>(errors.size());
        for (Error error : errors) {
            problems.add(problem(error));
        }
        return List.copyOf(problems);
    }

    private static ValidationError problem(Error error) {
        String pointer = error.getInstanceLocation() == null
                ? ""
                : error.getInstanceLocation().toString();
        if ("required".equals(error.getKeyword()) && error.getProperty() != null) {
            pointer = pointer + "/" + escape(error.getProperty());
        }
        return new ValidationError(
                pointer.isEmpty() ? "/" : pointer, code(error.getKeyword()), error.getMessage());
    }

    private static ValidationCode code(String keyword) {
        return switch (keyword) {
            case "required" -> ValidationCode.REQUIRED;
            case "maxItems", "minItems", "maxLength", "minLength", "minimum", "maximum", "maxProperties",
                    "uniqueItems" -> ValidationCode.LIMIT_EXCEEDED;
            default -> ValidationCode.UNKNOWN_TYPE;
        };
    }

    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private static String read(String resource) {
        try (InputStream stream = JsonSchemaDocumentVerifier.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("the contract " + resource
                        + " is not on the classpath; the build must package contracts/survey");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read " + resource, failure);
        }
    }
}
