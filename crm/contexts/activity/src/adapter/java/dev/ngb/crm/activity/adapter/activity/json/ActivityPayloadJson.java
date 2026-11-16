package dev.ngb.crm.activity.adapter.activity.json;

import dev.ngb.crm.activity.application.activity.service.ActivityPayloads;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A writer's payload as the JSON object it is stored as.
 *
 * <p>The writer emits the keys in order, so the same payload always gives the same bytes: a stored body and a sent body
 * that say the same thing compare equal, which is what makes a repeated request a replay rather than a conflict.
 */
public class ActivityPayloadJson implements ActivityPayloads {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    @Override
    public String toJson(Map<String, Object> payload) {
        return JSON.writeValueAsString(payload == null ? Map.of() : new TreeMap<>(payload));
    }

    @Override
    public Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return new LinkedHashMap<>(JSON.<Map<String, Object>>readValue(json, OBJECT));
    }

    @Override
    public int byteLength(Map<String, Object> payload) {
        return toJson(payload).getBytes(StandardCharsets.UTF_8).length;
    }
}
