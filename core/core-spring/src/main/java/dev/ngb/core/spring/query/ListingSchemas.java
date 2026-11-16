package dev.ngb.core.spring.query;

import dev.ngb.core.query.FilterSchema;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reads the {@code SCHEMA} constant a {@link Listing} names, once per class. */
public final class ListingSchemas {

    private static final Map<Class<?>, FilterSchema> CACHE = new ConcurrentHashMap<>();

    private ListingSchemas() {}

    /** @return the schema declared by {@code holder}; an illegal holder fails with a message naming it */
    public static FilterSchema of(Class<?> holder) {
        return CACHE.computeIfAbsent(holder, ListingSchemas::read);
    }

    private static FilterSchema read(Class<?> holder) {
        try {
            Field field = holder.getField("SCHEMA");
            if (!Modifier.isStatic(field.getModifiers()) || !FilterSchema.class.equals(field.getType())) {
                throw new IllegalStateException(holder.getName() + ".SCHEMA must be a static FilterSchema");
            }
            return (FilterSchema) field.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(holder.getName() + " must declare public static final FilterSchema SCHEMA", e);
        }
    }
}
