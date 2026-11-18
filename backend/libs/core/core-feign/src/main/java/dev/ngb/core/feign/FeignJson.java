package dev.ngb.core.feign;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The one JSON configuration the clients trust. */
public final class FeignJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private FeignJson() {}

    /** An upstream may add a field before this client ships, so an unknown property is ignored, never fatal. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
