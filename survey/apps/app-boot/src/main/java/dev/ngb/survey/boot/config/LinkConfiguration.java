package dev.ngb.survey.boot.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The HMAC keys a link token is signed with.
 *
 * <p>There is deliberately no default secret: a blank key fails startup rather than letting anyone who can read this
 * source mint a link token for any survey in any tenant. A rotation adds a key and moves {@code active-key-id}; every
 * key still configured keeps verifying, so a link already handed out keeps working until its key is removed.
 */
@Configuration(proxyBeanMethods = false)
class LinkConfiguration {

    /** The verified keys, with the active key id resolved once so a request never re-reads configuration. */
    record LinkKeys(String activeKeyId, Map<String, String> keys) {

        LinkKeys {
            if (keys.isEmpty()) {
                throw new IllegalStateException(
                        "survey.links.keys is empty: a link token must be signed with a configured key");
            }
            if (!keys.containsKey(activeKeyId)) {
                throw new IllegalStateException("survey.links.active-key-id names no configured key: " + activeKeyId);
            }
            for (Map.Entry<String, String> key : keys.entrySet()) {
                if (key.getValue() == null || key.getValue().isBlank()) {
                    throw new IllegalStateException(
                            "survey.links.keys." + key.getKey() + " must define a non-blank secret");
                }
            }
            keys = Map.copyOf(new LinkedHashMap<>(keys));
        }
    }

    @Bean
    LinkKeys linkKeys(SurveySettings settings) {
        return new LinkKeys(settings.links().activeKeyId(), settings.links().keys());
    }
}
