package dev.ngb.booking.support;

import dev.ngb.core.query.FilterSchema;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.QueryParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a validated {@link ListQuery} from name/value pairs, the way the HTTP layer does. */
public final class ListQueries {

    private ListQueries() {}

    /** @param pairs alternating parameter names and values, such as {@code "limit", "10"} */
    public static ListQuery of(FilterSchema schema, String... pairs) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.put(pairs[i], List.of(pairs[i + 1]));
        }
        return QueryParser.parse(schema, params);
    }
}
