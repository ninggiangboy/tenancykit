package dev.ngb.mailroom.sending.application.tracking.service;

import java.util.Map;

/**
 * The tracking key set and the public base URL. It is built from configuration and handed to the codec and the
 * rewriter; neither reaches a property source.
 *
 * @param activeKeyId the key id new tokens carry
 * @param publicBaseUrl where a recipient reaches {@code /t/...}; a trailing slash is removed
 * @param keys every key that may still verify, including the active one
 */
public record TrackingSettings(String activeKeyId, String publicBaseUrl, Map<String, String> keys) {

    public TrackingSettings {
        if (activeKeyId == null || activeKeyId.isBlank()) {
            throw new IllegalStateException("mailroom.tracking.active-key-id is required");
        }
        if (keys == null || !keys.containsKey(activeKeyId)) {
            throw new IllegalStateException("mailroom.tracking.keys must define the active key id " + activeKeyId);
        }
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalStateException("mailroom.tracking.public-base-url is required");
        }
        publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        keys = Map.copyOf(keys);
    }

    public String key(String keyId) {
        return keys.get(keyId);
    }

    public String activeKey() {
        return keys.get(activeKeyId);
    }
}
