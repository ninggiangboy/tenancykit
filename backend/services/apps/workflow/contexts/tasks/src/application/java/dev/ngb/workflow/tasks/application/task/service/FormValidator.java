package dev.ngb.workflow.tasks.application.task.service;

import dev.ngb.workflow.tasks.application.task.exception.TaskUnprocessableException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * The small JSON Schema subset a task form may use: an object with typed members, required members, enums and numeric
 * bounds. It is deliberately not a general validator; a form is a hint to the person and a guard on the API, not a
 * second expression language.
 */
public final class FormValidator {

    private static final Set<String> TYPES = Set.of("string", "number", "integer", "boolean", "object", "array");

    private FormValidator() {}

    public static void validate(Object schema, Object output) {
        if (schema == null) {
            return;
        }
        Map<String, Object> document = object(schema, "the form schema is an object");
        Object type = document.get("type");
        if (type != null && !TYPES.contains(String.valueOf(type))) {
            throw new TaskUnprocessableException("the form schema names an unknown type " + type);
        }
        String declared = type == null ? "object" : String.valueOf(type);
        requireType(declared, output, "the output");
        if ("object".equals(declared) && document.get("properties") instanceof Map<?, ?> properties) {
            Map<String, Object> values = output instanceof Map<?, ?> members ? cast(members) : Map.of();
            if (document.get("required") instanceof java.util.List<?> required) {
                for (Object name : required) {
                    if (!values.containsKey(String.valueOf(name))) {
                        throw new TaskUnprocessableException("the output needs the member " + name);
                    }
                }
            }
            for (Object raw : properties.keySet()) {
                String name = String.valueOf(raw);
                if (!values.containsKey(name)) {
                    continue;
                }
                Object memberSchema = properties.get(raw);
                if (!(memberSchema instanceof Map<?, ?> member)) {
                    continue;
                }
                validate(member, values.get(name));
            }
        }
        if (document.get("enum") instanceof java.util.List<?> allowed && !allowed.contains(output)) {
            throw new TaskUnprocessableException("the output is not one of " + allowed);
        }
        if (output instanceof Number number) {
            BigDecimal value = new BigDecimal(number.toString());
            if (document.get("minimum") instanceof Number minimum
                    && value.compareTo(new BigDecimal(minimum.toString())) < 0) {
                throw new TaskUnprocessableException("the output is below its minimum");
            }
            if (document.get("maximum") instanceof Number maximum
                    && value.compareTo(new BigDecimal(maximum.toString())) > 0) {
                throw new TaskUnprocessableException("the output is above its maximum");
            }
        }
    }

    private static void requireType(String declared, Object value, String label) {
        if (value == null) {
            return;
        }
        boolean matches = switch (declared) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue());
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof java.util.List<?>;
            default -> false;
        };
        if (!matches) {
            throw new TaskUnprocessableException(label + " is not a " + declared);
        }
    }

    private static Map<String, Object> object(Object value, String message) {
        if (value instanceof Map<?, ?> members) {
            return cast(members);
        }
        throw new TaskUnprocessableException(message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> members) {
        return (Map<String, Object>) members;
    }
}
