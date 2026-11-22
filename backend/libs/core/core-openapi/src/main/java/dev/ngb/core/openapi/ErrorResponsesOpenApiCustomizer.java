package dev.ngb.core.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * Documents the errors an operation can answer, so a generated client and a reader see them without reading the code.
 * Every error of the suite is an RFC 9457 body of media type {@code application/problem+json}; the {@code ProblemDetail}
 * schema describes it.
 *
 * <p>What the document itself shows decides which errors are listed: {@code 400} for an operation with a body or a
 * parameter a caller can get wrong (a free-text one cannot be), {@code 401} and {@code 403} unless it is public,
 * {@code 404} when a path that is not public names a resource, {@code 412} when it takes {@code If-Match} and
 * {@code 409} when it takes {@code Idempotency-Key}. The shared ones are
 * components, referenced from each operation. A response the operation already has keeps its text and gains the body.
 *
 * <p>The rest comes from the service's descriptions file, one line per response, which replaces a derived one:
 * {@code error METHOD /path status = text}, or {@code error METHOD /path status Schema = text} when the body is not a
 * {@code ProblemDetail}. A line with no text removes the response, for an operation that never answers it. A line that names no operation of the document fails at startup, so a typo cannot hide.
 */
public class ErrorResponsesOpenApiCustomizer implements OpenApiCustomizer {

    private static final String PROBLEM = "ProblemDetail";

    private static final String PROBLEM_JSON = "application/problem+json";

    private static final String SCHEMA_REF = "#/components/schemas/";

    /** The shared responses: component name, status and the key of its text. */
    private record Shared(String name, String status, String key) {}

    private static final Shared BAD_REQUEST = new Shared("BadRequest", "400", "response 400");
    private static final Shared UNAUTHENTICATED = new Shared("Unauthenticated", "401", "response 401");
    private static final Shared FORBIDDEN = new Shared("Forbidden", "403", "response 403");
    private static final Shared NOT_ENTITLED = new Shared("NotEntitled", "403", "response 403 entitlement");
    private static final Shared NOT_FOUND = new Shared("NotFound", "404", "response 404");
    private static final Shared IDEMPOTENCY_CONFLICT = new Shared("IdempotencyConflict", "409", "response 409 idempotency");
    private static final Shared PRECONDITION_FAILED = new Shared("PreconditionFailed", "412", "response 412");

    private static final List<Shared> ALL = List.of(
            BAD_REQUEST, UNAUTHENTICATED, FORBIDDEN, NOT_ENTITLED, NOT_FOUND, IDEMPOTENCY_CONFLICT, PRECONDITION_FAILED);

    private record Explicit(String schema, String text) {}

    private final Map<String, String> text;

    /** {@code METHOD path} to the status of each response the service describes for it. */
    private final Map<String, Map<String, Explicit>> explicit = new HashMap<>();

    /** @param resource the classpath location of the service's descriptions file */
    public ErrorResponsesOpenApiCustomizer(String resource) {
        text = DescriptionsOpenApiCustomizer.load(resource);
        text.forEach((key, value) -> {
            if (!key.startsWith("error ")) {
                return;
            }
            String[] parts = key.split(" ");
            if (parts.length < 4 || parts.length > 5) {
                throw new IllegalStateException(resource + ": expected 'error METHOD /path status [Schema] = text' but found: " + key);
            }
            explicit.computeIfAbsent(parts[1] + " " + parts[2], k -> new LinkedHashMap<>())
                    .put(parts[3], new Explicit(parts.length == 5 ? parts[4] : PROBLEM, value));
        });
    }

    @Override
    public void customise(OpenAPI api) {
        if (api.getPaths() == null) {
            return;
        }
        Components components = api.getComponents() == null ? new Components() : api.getComponents();
        api.setComponents(components);
        if (components.getSchemas() == null || !components.getSchemas().containsKey(PROBLEM)) {
            components.addSchemas(PROBLEM, problemSchema());
        }
        for (Shared shared : ALL) {
            components.addResponses(shared.name(), shared(shared));
        }
        Set<String> documented = new HashSet<>();
        api.getPaths().forEach((path, item) ->
                item.readOperationsMap().forEach((method, operation) -> {
                    documented.add(method.name() + " " + path);
                    document(path, method.name(), operation);
                }));
        List<String> unmatched = explicit.keySet().stream().filter(key -> !documented.contains(key)).sorted().toList();
        if (!unmatched.isEmpty()) {
            throw new IllegalStateException("The descriptions file has error lines for operations that do not exist: " + unmatched);
        }
    }

    private void document(String path, String method, Operation operation) {
        Map<String, ApiResponse> derived = new LinkedHashMap<>();
        boolean isPublic = operation.getExtensions() != null && operation.getExtensions().containsKey("x-public");
        boolean needsPermission = operation.getExtensions() != null && operation.getExtensions().containsKey("x-required-permission");
        boolean mayBeMalformed = operation.getRequestBody() != null
                || (operation.getParameters() != null && operation.getParameters().stream().anyMatch(ErrorResponsesOpenApiCustomizer::mayBeMalformed));
        if (mayBeMalformed) {
            derived.put(BAD_REQUEST.status(), reference(BAD_REQUEST));
        }
        if (!isPublic) {
            derived.put(UNAUTHENTICATED.status(), reference(UNAUTHENTICATED));
            derived.put("403", reference(needsPermission ? FORBIDDEN : NOT_ENTITLED));
        }
        if (path.contains("{") && !isPublic) {
            derived.put(NOT_FOUND.status(), reference(NOT_FOUND));
        }
        if (hasHeader(operation, "If-Match")) {
            derived.put(PRECONDITION_FAILED.status(), reference(PRECONDITION_FAILED));
        }
        if (hasHeader(operation, "Idempotency-Key")) {
            derived.put(IDEMPOTENCY_CONFLICT.status(), reference(IDEMPOTENCY_CONFLICT));
        }
        ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
        derived.forEach((status, response) -> responses.putIfAbsent(status, response));
        explicit.getOrDefault(method + " " + path, Map.of()).forEach((status, given) -> {
            if (given.text().isEmpty()) {
                responses.remove(status);
            } else {
                responses.put(status, inline(given));
            }
        });
        for (Map.Entry<String, ApiResponse> response : responses.entrySet()) {
            ApiResponse value = response.getValue();
            if (isError(response.getKey()) && value.get$ref() == null && value.getContent() == null) {
                value.setContent(problemContent(PROBLEM));
            }
        }
        operation.setResponses(sorted(responses));
    }

    /**
     * Whether a caller can get this parameter wrong: a required header or query parameter that may be missing, or a value
     * with a type to violate (a number, a flag, an id, a date or one of a list). A free-text parameter cannot be malformed.
     */
    private static boolean mayBeMalformed(Parameter parameter) {
        boolean required = !"path".equals(parameter.getIn()) && Boolean.TRUE.equals(parameter.getRequired());
        return required || hasType(parameter.getSchema());
    }

    private static boolean hasType(Schema<?> schema) {
        if (schema == null) {
            return false;
        }
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            return true;
        }
        String type = schema.getType() != null ? schema.getType()
                : (schema.getTypes() == null || schema.getTypes().isEmpty() ? null : schema.getTypes().iterator().next());
        String format = schema.getFormat();
        return List.of("integer", "number", "boolean").contains(type)
                || (format != null && List.of("uuid", "date", "date-time", "duration").contains(format));
    }

    static ApiResponses sorted(ApiResponses responses) {
        List<Map.Entry<String, ApiResponse>> entries = new ArrayList<>(responses.entrySet());
        entries.sort((a, b) -> Integer.compare(order(a.getKey()), order(b.getKey())));
        ApiResponses result = new ApiResponses();
        result.setExtensions(responses.getExtensions());
        entries.forEach(entry -> result.addApiResponse(entry.getKey(), entry.getValue()));
        return result;
    }

    private static int order(String status) {
        return status.chars().allMatch(Character::isDigit) ? Integer.parseInt(status) : 0;
    }

    private static boolean isError(String status) {
        return status.length() == 3 && (status.startsWith("4") || status.startsWith("5"));
    }

    private static boolean hasHeader(Operation operation, String name) {
        if (operation.getParameters() == null) {
            return false;
        }
        for (Parameter parameter : operation.getParameters()) {
            if ("header".equals(parameter.getIn()) && name.equalsIgnoreCase(parameter.getName())) {
                return true;
            }
        }
        return false;
    }

    private static ApiResponse reference(Shared shared) {
        return new ApiResponse().$ref(shared.name());
    }

    private ApiResponse shared(Shared shared) {
        String description = text.get(shared.key());
        if (description == null) {
            throw new IllegalStateException("The built-in text '" + shared.key() + "' is missing");
        }
        ApiResponse response = new ApiResponse().description(description).content(problemContent(PROBLEM));
        if (shared == PRECONDITION_FAILED) {
            response.addHeaderObject("ETag", new Header().description("The current version of the resource.").schema(new StringSchema()));
        }
        return response;
    }

    private static ApiResponse inline(Explicit given) {
        return new ApiResponse().description(given.text()).content(problemContent(given.schema()));
    }

    private static Content problemContent(String schema) {
        return new Content().addMediaType(PROBLEM_JSON, new MediaType().schema(new Schema<>().$ref(SCHEMA_REF + schema)));
    }

    private Schema<?> problemSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.setDescription(text.get("schema ProblemDetail"));
        schema.addProperty("type", described(new StringSchema(), "type"));
        schema.addProperty("title", described(new StringSchema(), "title"));
        schema.addProperty("status", described(new IntegerSchema().format("int32"), "status"));
        schema.addProperty("detail", described(new StringSchema(), "detail"));
        schema.addProperty("instance", described(new StringSchema(), "instance"));
        return schema;
    }

    private Schema<?> described(Schema<?> property, String name) {
        property.setDescription(text.get("schema ProblemDetail." + name));
        return property;
    }
}
