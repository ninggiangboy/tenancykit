package dev.ngb.core.query;

import dev.ngb.core.error.BadRequestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The whitelist of one resource: every field a caller may filter or sort on, the order a listing has when the caller
 * names none, and the unique field that makes any ordering total.
 *
 * <p>The tie-breaker is appended to every ordering, so a cursor always identifies exactly one row. It must be sortable
 * and unique per resource ({@code id} for most; the sequence for a ledger).
 */
public final class FilterSchema {

    private final Map<String, FieldSpec> fields;
    private final FieldSpec tieBreaker;
    private final List<SortKey> defaultSort;

    private FilterSchema(Map<String, FieldSpec> fields, FieldSpec tieBreaker, List<SortKey> defaultSort) {
        this.fields = fields;
        this.tieBreaker = tieBreaker;
        this.defaultSort = defaultSort;
    }

    /**
     * @param tieBreaker the name of the unique, sortable field appended to every ordering
     * @param defaultSort the ordering when the caller sends none, in {@code sort} syntax such as {@code -created_at}
     * @param specs the whitelisted fields
     */
    public static FilterSchema of(String tieBreaker, String defaultSort, FieldSpec... specs) {
        Map<String, FieldSpec> byName = new LinkedHashMap<>();
        for (FieldSpec spec : specs) {
            if (byName.put(spec.name(), spec) != null) {
                throw new IllegalArgumentException("the field " + spec.name() + " is declared twice");
            }
        }
        if (defaultSort == null || defaultSort.isBlank()) {
            throw new IllegalArgumentException("a schema declares its default sort");
        }
        FieldSpec tie = byName.get(tieBreaker);
        if (tie == null || !tie.sortable()) {
            throw new IllegalArgumentException("the tie-breaker " + tieBreaker + " must be a declared sortable field");
        }
        FilterSchema probe = new FilterSchema(Map.copyOf(byName), tie, List.of());
        List<SortKey> sort = probe.parseSort(defaultSort);
        return new FilterSchema(probe.fields, tie, sort);
    }

    public Optional<FieldSpec> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    /** @return every declared field, ordered by name */
    public List<FieldSpec> fields() {
        return fields.values().stream().sorted(java.util.Comparator.comparing(FieldSpec::name)).toList();
    }

    public FieldSpec tieBreaker() {
        return tieBreaker;
    }

    /** @return the ordering used when the caller sends no {@code sort}, tie-breaker included */
    public List<SortKey> defaultSort() {
        return defaultSort;
    }

    /**
     * @param raw a {@code sort} value such as {@code -created_at,total}; null or blank means the default
     * @return the ordering with the tie-breaker appended when the caller did not name it
     * @throws BadRequestException when a term is unknown, not sortable, repeated, or there are too many
     */
    List<SortKey> parseSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultSort;
        }
        List<SortKey> keys = new ArrayList<>();
        for (String term : raw.split(",", -1)) {
            boolean descending = term.startsWith("-");
            String name = descending ? term.substring(1) : term;
            FieldSpec spec = fields.get(name);
            if (spec == null || !spec.sortable()) {
                throw new BadRequestException("the field " + name + " cannot be sorted; sortable fields: " + sortableNames());
            }
            if (keys.stream().anyMatch(key -> key.field().equals(spec))) {
                throw new BadRequestException("the field " + name + " is sorted twice");
            }
            keys.add(new SortKey(spec, descending));
        }
        if (keys.size() > QueryParser.MAX_SORT_KEYS) {
            throw new BadRequestException("a listing sorts by at most " + QueryParser.MAX_SORT_KEYS + " fields");
        }
        if (keys.stream().noneMatch(key -> key.field().equals(tieBreaker))) {
            keys.add(new SortKey(tieBreaker, false));
        }
        return List.copyOf(keys);
    }

    String filterableNames() {
        return String.join(", ", fields().stream().map(FieldSpec::name).toList());
    }

    private String sortableNames() {
        return String.join(", ", fields().stream().filter(FieldSpec::sortable).map(FieldSpec::name).toList());
    }
}
