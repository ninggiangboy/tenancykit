package dev.ngb.booking.catalog.application.resource.service;

import dev.ngb.booking.catalog.application.catalog.exception.CatalogValidationException;
import dev.ngb.booking.catalog.domain.resource.ResourceStatus;
import java.time.ZoneId;
import java.util.Locale;

/**
 * What a resource must be before it is stored. The rules live in one place, so the endpoints that create, read and
 * change a resource cannot disagree about them.
 */
public final class ResourceRules {

    /** The longest code a Tenant may choose for a resource. */
    public static final int MAX_CODE = 60;

    /** The longest name a Tenant may show for a resource. */
    public static final int MAX_NAME = 200;

    private ResourceRules() {}

    /** The Tenant's code: trimmed, 1 to {@value #MAX_CODE} characters, unique per tenant and immutable. */
    public static String code(String raw) {
        return required(raw, MAX_CODE, "A resource code");
    }

    public static String name(String raw) {
        return required(raw, MAX_NAME, "A resource name");
    }

    /** An IANA zone id the JDK's tz database knows, so a local time can always be resolved in it. */
    public static String zone(String raw) {
        if (raw == null) {
            throw new CatalogValidationException("A zone is required.");
        }
        String zone = raw.strip();
        if (zone.isEmpty() || !ZoneId.getAvailableZoneIds().contains(zone)) {
            throw new CatalogValidationException(zone.isEmpty()
                    ? "A zone is required."
                    : "The zone " + zone + " is not an IANA id this server knows.");
        }
        return zone;
    }

    /** How many seats a resource holds, or null when it is not seat-limited; a seat count is at least one. */
    public static Integer seats(Integer raw) {
        if (raw == null) {
            return null;
        }
        if (raw < 1) {
            throw new CatalogValidationException("A resource holds at least one seat.");
        }
        return raw;
    }

    /** The status a caller names, or null when the body left it alone. */
    public static ResourceStatus status(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return ResourceStatus.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new CatalogValidationException("A resource status is ACTIVE or ARCHIVED.");
        }
    }

    private static String required(String raw, int max, String what) {
        if (raw == null) {
            throw new CatalogValidationException(what + " is required.");
        }
        String value = raw.strip();
        if (value.isEmpty() || value.length() > max) {
            throw new CatalogValidationException(what + " is 1 to " + max + " characters.");
        }
        return value;
    }
}
