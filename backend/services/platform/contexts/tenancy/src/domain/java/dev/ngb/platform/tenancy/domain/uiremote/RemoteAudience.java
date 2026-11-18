package dev.ngb.platform.tenancy.domain.uiremote;

import java.util.Locale;

/** Which shell a remote may be mounted in. A remote may list both: {@code remote-platform} serves both audiences. */
public enum RemoteAudience {
    PLATFORM,
    TENANT;

    /** @throws IllegalArgumentException when the value is not an audience */
    public static RemoteAudience parse(String value) {
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("an audience is PLATFORM or TENANT, not " + value);
        }
    }
}
