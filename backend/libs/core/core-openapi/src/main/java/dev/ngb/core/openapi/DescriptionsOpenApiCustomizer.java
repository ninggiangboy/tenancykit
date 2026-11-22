package dev.ngb.core.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.core.io.ClassPathResource;

/**
 * Describes the parts of the document a controller signature cannot: request and response fields, parameters, headers
 * and bodies. A service keeps those words in one reviewable file instead of an annotation on every record, so the
 * plain-Java application layer, whose views are also served, stays free of the documentation library.
 *
 * <p>The file is read once. A line is {@code key = text}; a line that starts with whitespace continues the text above
 * it, and {@code #} starts a comment. The keys, most specific first:
 * <ul>
 *   <li>{@code schema Name} describes a schema, and {@code schema Name.field} one of its fields;</li>
 *   <li>{@code field name} describes a field of any schema that has no entry of its own;</li>
 *   <li>{@code param /path name} describes one parameter of one path, and {@code param name} a parameter anywhere;</li>
 *   <li>{@code body /path} describes a request body, and {@code body /path field} one field of a form body, that no
 *       schema describes.</li>
 * </ul>
 * <p>A success status springdoc cannot see (a handler that answers {@code 201} or {@code 200} by what it did, {@code 204}
 * or {@code 302} through a {@code ResponseEntity}) is stated with {@code status METHOD /path code = text}: the first
 * success response of the operation is replaced by the declared ones, and each takes the body of the one it replaces
 * unless it is a {@code 204} or a redirect. A line that names no operation fails at startup.
 *
 * A path parameter nobody described is derived from the resource before it: {@code {id}} after {@code roles} reads
 * "The id of the role." Nothing already described is overwritten. A request body and a successful response that only
 * carry a default status text take the description of the schema they refer to.
 *
 * <p>The suite's own entries (the paging facts, the headers every service reads) are built in; a service's file adds to
 * them and wins on a clash.
 */
public class DescriptionsOpenApiCustomizer implements OpenApiCustomizer {

    private static final String BUILT_IN = "descriptions.conf";

    private static final String REF_PREFIX = "#/components/schemas/";

    private static final java.util.Set<String> DEFAULT_TEXTS = java.util.Set.of("OK", "Created", "Accepted", "No Content");

    private final Map<String, String> text = new LinkedHashMap<>();

    /** {@code METHOD path} to the success statuses the service declares for it, in the order given. */
    private final Map<String, Map<String, String>> statuses = new LinkedHashMap<>();

    /** @param resource the classpath location of the service's descriptions file */
    public DescriptionsOpenApiCustomizer(String resource) {
        text.putAll(load(resource));
        text.forEach((key, value) -> {
            if (!key.startsWith("status ")) {
                return;
            }
            String[] parts = key.split(" ");
            if (parts.length != 4) {
                throw new IllegalStateException(resource + ": expected 'status METHOD /path code = text' but found: " + key);
            }
            statuses.computeIfAbsent(parts[1] + " " + parts[2], k -> new LinkedHashMap<>()).put(parts[3], value);
        });
    }

    /** The suite's built-in entries with the service's own on top, as {@code key} to text. */
    static Map<String, String> load(String resource) {
        Map<String, String> entries = new LinkedHashMap<>(read(new ClassPathResource(BUILT_IN, DescriptionsOpenApiCustomizer.class)));
        entries.putAll(read(new ClassPathResource(resource)));
        return entries;
    }

    @Override
    public void customise(OpenAPI api) {
        Map<String, Schema<?>> schemas = new LinkedHashMap<>();
        if (api.getComponents() != null && api.getComponents().getSchemas() != null) {
            api.getComponents().getSchemas().forEach(schemas::put);
        }
        schemas.forEach(this::describeSchema);
        if (api.getPaths() != null) {
            Set<String> documented = new HashSet<>();
            api.getPaths().forEach((path, item) ->
                    item.readOperationsMap().forEach((method, operation) -> {
                        documented.add(method.name() + " " + path);
                        describeOperation(method.name(), path, operation, schemas);
                    }));
            List<String> unmatched = statuses.keySet().stream().filter(key -> !documented.contains(key)).sorted().toList();
            if (!unmatched.isEmpty()) {
                throw new IllegalStateException("The descriptions file has status lines for operations that do not exist: " + unmatched);
            }
        }
    }

    private void describeSchema(String name, Schema<?> schema) {
        boolean pageWrapper = isPageWrapper(name, schema);
        if (schema.getDescription() == null) {
            String own = text.get("schema " + name);
            if (own == null && pageWrapper) {
                own = "A page of " + humanize(name.substring("Page".length())) + " rows, with the paging facts when the "
                        + "request asked for them.";
            }
            schema.setDescription(own);
        }
        if (schema.getProperties() == null) {
            return;
        }
        schema.getProperties().forEach((field, property) -> {
            if (!(property instanceof Schema<?> given) || given.getDescription() != null) {
                return;
            }
            String described = text.get("schema " + name + "." + field);
            if (described == null) {
                described = text.get("field " + field);
            }
            if (described == null && pageWrapper) {
                described = "data".equals(field) ? "The rows of this page."
                        : "The paging facts, or null when the request did not ask for them with `page_info`.";
            }
            given.setDescription(described);
        });
    }

    private static boolean isPageWrapper(String name, Schema<?> schema) {
        return name.startsWith("Page") && name.length() > "Page".length() && schema.getProperties() != null
                && schema.getProperties().containsKey("data") && schema.getProperties().containsKey("page");
    }

    private void describeOperation(String method, String path, Operation operation, Map<String, Schema<?>> schemas) {
        Map<String, String> declared = statuses.get(method + " " + path);
        if (declared != null) {
            declareStatuses(method + " " + path, operation, declared);
        }
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                if (parameter.getDescription() == null && parameter.getName() != null) {
                    parameter.setDescription(parameterText(path, parameter));
                }
            }
        }
        RequestBody body = operation.getRequestBody();
        if (body != null) {
            describeBody(path, body, schemas);
        }
        if (operation.getResponses() != null) {
            for (Map.Entry<String, ApiResponse> response : operation.getResponses().entrySet()) {
                ApiResponse value = response.getValue();
                if (response.getKey().startsWith("2") && value.getContent() != null
                        && (value.getDescription() == null || DEFAULT_TEXTS.contains(value.getDescription()))) {
                    String described = schemaText(value.getContent(), schemas);
                    if (described != null) {
                        value.setDescription(described);
                    }
                }
            }
        }
    }

    private static void declareStatuses(String operationName, Operation operation, Map<String, String> declared) {
        ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
        String primaryKey = responses.keySet().stream().filter(key -> key.startsWith("2")).findFirst().orElse(null);
        ApiResponse base = primaryKey == null ? null : responses.get(primaryKey);
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            String code = entry.getKey();
            ApiResponse response = responses.get(code);
            if (response == null) {
                response = new ApiResponse();
                if (base != null && base.getContent() != null && !code.equals("204") && !code.startsWith("3")) {
                    response.setContent(base.getContent());
                }
                responses.put(code, response);
            }
            if (!entry.getValue().isEmpty()) {
                response.setDescription(entry.getValue());
            } else if (response.getDescription() == null && base != null) {
                response.setDescription(base.getDescription());
            }
            if (response.getDescription() == null) {
                throw new IllegalStateException("The status line of " + operationName + " " + code + " needs a text");
            }
        }
        if (primaryKey != null && !declared.containsKey(primaryKey)) {
            responses.remove(primaryKey);
        }
        operation.setResponses(ErrorResponsesOpenApiCustomizer.sorted(responses));
    }

    /** A body names its path, {@code body /path}, when it is not a referenced schema; a form's fields add their own name. */
    private void describeBody(String path, RequestBody body, Map<String, Schema<?>> schemas) {
        if (body.getDescription() == null) {
            String own = text.get("body " + path);
            body.setDescription(own != null ? own : schemaText(body.getContent(), schemas));
        }
        if (body.getContent() == null) {
            return;
        }
        for (MediaType media : body.getContent().values()) {
            Schema<?> schema = media.getSchema();
            if (schema == null || schema.get$ref() != null || schema.getProperties() == null) {
                continue;
            }
            schema.getProperties().forEach((field, property) -> {
                if (property instanceof Schema<?> given && given.getDescription() == null) {
                    given.setDescription(text.get("body " + path + " " + field));
                }
            });
        }
    }

    private String parameterText(String path, Parameter parameter) {
        String name = parameter.getName();
        String specific = text.get("param " + path + " " + name);
        if (specific != null) {
            return specific;
        }
        String general = text.get("param " + name);
        if (general != null) {
            return general;
        }
        return "path".equals(parameter.getIn()) ? pathParameterText(path, name) : null;
    }

    /** "The id of the role." for {@code {id}} after {@code roles}; the name alone when no resource precedes it. */
    private static String pathParameterText(String path, String name) {
        String[] segments = path.split("/");
        String noun = null;
        for (int i = 1; i < segments.length; i++) {
            if (segments[i].equals("{" + name + "}") && !segments[i - 1].isEmpty() && !segments[i - 1].startsWith("{")) {
                noun = singular(humanize(segments[i - 1]));
            }
        }
        String label = humanize(name);
        if (noun == null || label.startsWith(noun)) {
            return "The " + label + ".";
        }
        return "The " + label + " of the " + noun + ".";
    }

    private static String schemaText(Content content, Map<String, Schema<?>> schemas) {
        if (content == null) {
            return null;
        }
        for (MediaType media : content.values()) {
            Schema<?> schema = media.getSchema();
            if (schema == null || schema.get$ref() == null || !schema.get$ref().startsWith(REF_PREFIX)) {
                continue;
            }
            Schema<?> target = schemas.get(schema.get$ref().substring(REF_PREFIX.length()));
            if (target != null && target.getDescription() != null) {
                return target.getDescription();
            }
        }
        return null;
    }

    private static String singular(String noun) {
        if (noun.endsWith("ies")) {
            return noun.substring(0, noun.length() - 3) + "y";
        }
        if (noun.endsWith("ses") || noun.endsWith("xes")) {
            return noun.substring(0, noun.length() - 2);
        }
        return noun.endsWith("s") && !noun.endsWith("ss") ? noun.substring(0, noun.length() - 1) : noun;
    }

    /** {@code externalRef} and {@code tenant-systems} read as "external ref" and "tenant systems". */
    private static String humanize(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('-', ' ').replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
    }

    private static Map<String, String> read(ClassPathResource resource) {
        if (!resource.exists()) {
            throw new IllegalStateException("The OpenAPI descriptions file " + resource.getPath() + " is missing");
        }
        Map<String, String> entries = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String key = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (Character.isWhitespace(line.charAt(0))) {
                    if (key == null) {
                        throw new IllegalStateException(resource.getPath() + ": a continuation line has nothing to continue");
                    }
                    entries.put(key, (entries.get(key) + " " + line.strip()).strip());
                    continue;
                }
                int split = line.indexOf(" = ");
                if (split >= 0) {
                    key = line.substring(0, split).strip();
                    entries.put(key, line.substring(split + 3).strip());
                } else if (line.endsWith(" =")) {
                    key = line.substring(0, line.length() - 2).strip();
                    entries.put(key, "");
                } else {
                    throw new IllegalStateException(resource.getPath() + ": expected 'key = text' but found: " + line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }
}
