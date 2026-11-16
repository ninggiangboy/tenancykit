package dev.ngb.core.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Only the five UTM keys survive, each bounded to 100 characters with control characters removed. */
class BoundedParamsTest {

    @Test
    void onlyTheFiveUtmKeysSurvive() {
        Map<String, Object> params = new HashMap<>();
        params.put("utm_source", "newsletter");
        params.put("utm_medium", "email");
        params.put("utm_campaign", "spring");
        params.put("utm_term", "shoes");
        params.put("utm_content", "banner");
        params.put("gclid", "must-not-survive");
        params.put("email", "someone@example.com");

        assertThat(BoundedParams.utm(params)).containsOnlyKeys(
                "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content");
    }

    @Test
    void aValueIsCappedAtOneHundredCharacters() {
        String longValue = "a".repeat(500);
        assertThat(BoundedParams.utm(Map.of("utm_source", longValue)).get("utm_source")).hasSize(100);
    }

    @Test
    void controlCharactersAreRemoved() {
        assertThat(BoundedParams.utm(Map.of("utm_source", "new\u0000line\nand\ttab")).get("utm_source"))
                .isEqualTo("newlineandtab");
    }

    @Test
    void aBlankOrNonTextValueIsDropped() {
        Map<String, Object> params = new HashMap<>();
        params.put("utm_source", "   ");
        params.put("utm_medium", 42);
        assertThat(BoundedParams.utm(params)).isEmpty();
    }

    @Test
    void aNullOrEmptyMapGivesAnEmptyResult() {
        assertThat(BoundedParams.utm(null)).isEmpty();
        assertThat(BoundedParams.utm(Map.of())).isEmpty();
    }

    @Test
    void theResultIsImmutable() {
        Map<String, String> kept = BoundedParams.utm(Map.of("utm_source", "x"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> kept.put("utm_medium", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aUtmKeyIsRecognised() {
        assertThat(BoundedParams.isUtmKey("utm_campaign")).isTrue();
        assertThat(BoundedParams.isUtmKey("gclid")).isFalse();
        assertThat(BoundedParams.isUtmKey(null)).isFalse();
    }
}
