package dev.ngb.survey.insight.adapter.export.persistence;

import java.util.Map;
import org.jooq.JSONB;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Reads the stored filter JSON, with the one codec the two export repositories share. */
final class Filters {

    private static final TypeReference<Map<String, Object>> FILTER = new TypeReference<>() {};
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Filters() {}

    static Map<String, Object> read(JSONB stored) {
        Map<String, Object> filter = stored == null ? null : JSON.readValue(stored.data(), FILTER);
        return filter == null ? Map.of() : filter;
    }
}
