package dev.ngb.crm.customers.domain.field;

/**
 * What a Tenant-defined customer attribute may hold. The type is fixed when the definition is created: a value stored
 * under a key keeps its meaning for as long as the key exists, and a Tenant that wants another type defines another
 * field.
 */
public enum FieldType {

    /** A short free-text value. */
    TEXT,

    /** A number, stored as a decimal so a value written as {@code 1.50} and read back as {@code 1.5} is one value. */
    NUMBER,

    /** An ISO-8601 calendar date, {@code yyyy-MM-dd}, stored as the text it was sent as. */
    DATE,

    /** A true or false value. */
    BOOLEAN,

    /** One of the definition's own choices. */
    CHOICE
}
