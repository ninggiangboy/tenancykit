package dev.ngb.core.query;

/** One term of an ordering: a sortable field and its direction. */
public record SortKey(FieldSpec field, boolean descending) {

    /** @return the term as it is written in {@code sort}: {@code -created_at} or {@code total} */
    public String wire() {
        return (descending ? "-" : "") + field.name();
    }
}
