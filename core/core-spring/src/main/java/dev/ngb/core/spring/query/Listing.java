package dev.ngb.core.spring.query;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as a listing that follows the list-query contract. The {@code ListQuery} parameter of the
 * method is filled from the query string against the schema this names, and the OpenAPI operation is generated from
 * the same schema, so the documentation cannot differ from what the endpoint accepts.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Listing {

    /** A class that declares {@code public static final FilterSchema SCHEMA}. */
    Class<?> value();

    /** Query parameters the method reads itself, which the contract must not refuse. */
    String[] passthrough() default {};
}
