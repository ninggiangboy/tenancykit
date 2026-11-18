package dev.ngb.attribution.boot.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The app's signing and hashing keys, checked at startup.
 *
 * <p>A blank click secret or visitor hash key must fail the boot rather than sign every visitor's link with a public
 * string, exactly as mailroom's tracking configuration refuses to start without its key. The check lives here rather
 * than in a record's compact constructor so the failure names the property a deployment must set.
 *
 * <p>Phase 5 uses the click keys and phase 7 the visitor hash key; the bean exists from the skeleton because a
 * deployment that starts without them would be broken from the first redirect.
 */
@Configuration(proxyBeanMethods = false)
class AttributionKeysConfiguration {

    @Bean
    AttributionKeys attributionKeys(AttributionSettings settings) {
        AttributionSettings.Keys keys = settings.keys();
        if (keys == null) {
            throw new IllegalStateException("attribution.keys is not configured");
        }
        String activeKeyId = keys.activeKeyId();
        Map<String, String> clickKeys = keys.clickKeys() == null ? Map.of() : keys.clickKeys();
        String activeSecret = clickKeys.get(activeKeyId);
        if (activeKeyId == null || activeKeyId.isBlank()) {
            throw new IllegalStateException("attribution.keys.active-key-id must name the current click-token key");
        }
        if (activeSecret == null || activeSecret.isBlank()) {
            throw new IllegalStateException(
                    "attribution.keys.click-keys." + activeKeyId
                            + " is empty (ATTRIBUTION_CLICK_SECRET); the app will not sign click tokens with nothing");
        }
        for (Map.Entry<String, String> key : clickKeys.entrySet()) {
            if (key.getValue() == null || key.getValue().isBlank()) {
                throw new IllegalStateException(
                        "attribution.keys.click-keys." + key.getKey() + " is empty; remove it or set its secret");
            }
        }
        if (keys.visitorHashKey() == null || keys.visitorHashKey().isBlank()) {
            throw new IllegalStateException(
                    "attribution.keys.visitor-hash-key is empty (ATTRIBUTION_VISITOR_HASH_KEY); the app will not hash a"
                            + " visitor with nothing");
        }
        return new AttributionKeys(
                activeKeyId,
                Map.copyOf(clickKeys),
                keys.visitorHashKey(),
                keys.publicBaseUrl(),
                keys.geoipPath());
    }

    /**
     * @param activeKeyId the id a newly issued click token carries
     * @param clickKeys every key id that still verifies, including the active one
     * @param visitorHashKey the master key the daily visitor hash derives from
     * @param publicBaseUrl where a visitor reaches the public endpoints
     * @param geoipPath the country database path, or empty when GeoIP is unavailable
     */
    record AttributionKeys(
            String activeKeyId,
            Map<String, String> clickKeys,
            String visitorHashKey,
            String publicBaseUrl,
            String geoipPath) {}
}
