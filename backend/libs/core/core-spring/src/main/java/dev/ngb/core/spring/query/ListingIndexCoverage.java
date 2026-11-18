package dev.ngb.core.spring.query;

import dev.ngb.core.query.FieldSpec;
import dev.ngb.core.query.Op;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Field;

/**
 * Finds the fields of a listing that a PostgreSQL index does not serve, so a schema cannot grow a sort or a text search
 * that scans the whole table. Meant for each service's integration test against a migrated database:
 *
 * <ul>
 *   <li>a sortable field must lead at least one full (non-partial) index of its table;
 *   <li>a field that allows {@code contains} must have a full GIN trigram index on its column.
 * </ul>
 *
 * Related fields are checked against their own table.
 */
public final class ListingIndexCoverage {

    private static final Pattern DEFINITION = Pattern.compile(
            "(?i)^create (?:unique )?index \\S+ on (?:only )?(?:\\S+\\.)?\"?(\\w+)\"? using (\\w+) \\(([^)]*)\\)(?<tail>.*)$");

    private ListingIndexCoverage() {}

    /** @return one message per field without the index it needs; empty when the listing is covered */
    public static List<String> missing(DSLContext dsl, ListBinding binding) {
        List<String> missing = new ArrayList<>();
        for (FieldSpec spec : binding.schema().fields()) {
            boolean sorted = spec.sortable();
            boolean searched = spec.ops().contains(Op.CONTAINS);
            if (!sorted && !searched) {
                continue;
            }
            Field<Object> column = binding.column(spec.name());
            String table = column.getQualifiedName().parts()[column.getQualifiedName().parts().length - 2].first();
            List<String> definitions = dsl.fetch("select indexdef from pg_indexes where tablename = ?", table).getValues(0, String.class);
            if (sorted && definitions.stream().noneMatch(def -> leads(def, column.getName(), "btree"))) {
                missing.add(spec.name() + " is sortable but no full btree index on " + table + " starts with " + column.getName());
            }
            if (searched && definitions.stream().noneMatch(def -> trigram(def, column.getName()))) {
                missing.add(spec.name() + " allows contains but " + table + "." + column.getName() + " has no full gin_trgm_ops index");
            }
        }
        return missing;
    }

    private static boolean leads(String definition, String column, String method) {
        Matcher m = DEFINITION.matcher(definition);
        if (!m.matches() || !m.group(2).equalsIgnoreCase(method) || m.group("tail").toLowerCase().contains(" where ")) {
            return false;
        }
        String first = m.group(3).split(",")[0].trim().replace("\"", "");
        return first.equals(column) || first.startsWith(column + " ");
    }

    private static boolean trigram(String definition, String column) {
        Matcher m = DEFINITION.matcher(definition);
        return m.matches() && m.group(2).equalsIgnoreCase("gin") && !m.group("tail").toLowerCase().contains(" where ")
                && m.group(3).replace("\"", "").trim().matches(Pattern.quote(column) + "\\s+gin_trgm_ops");
    }
}
