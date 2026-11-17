package dev.ngb.workflow.triggers.application.trigger.service;

import java.util.List;
import java.util.Map;

/** The small amount of JSON tree handling this context needs, without a framework type. */
public final class Documents {

    private Documents() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> members ? (Map<String, Object>) members : null;
    }

    public static String text(Object value) {
        return value instanceof String text ? text : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        return value instanceof List<?> members ? (List<Object>) members : null;
    }
}
