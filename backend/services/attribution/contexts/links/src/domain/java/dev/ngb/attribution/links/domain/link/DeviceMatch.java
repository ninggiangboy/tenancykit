package dev.ngb.attribution.links.domain.link;

import dev.ngb.core.visitor.DeviceClass;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rule that decides whether a destination may receive a visitor.
 *
 * <p>An empty rule always passes. Otherwise the visitor's device class must be one of {@link #devices} and the country
 * one of {@link #countries}; each list that is present is a constraint, so a rule naming both requires both. A country
 * is an upper-case ISO 3166-1 alpha-2 code.
 */
public record DeviceMatch(List<DeviceClass> devices, List<String> countries) {

    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");

    public DeviceMatch {
        devices = devices == null ? List.of() : List.copyOf(devices);
        countries = countries == null
                ? List.of()
                : countries.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(code -> code.strip().toUpperCase(Locale.ROOT))
                        .filter(code -> COUNTRY.matcher(code).matches())
                        .distinct()
                        .toList();
    }

    /** The rule that every visitor passes, which is what a link with no match means. */
    public static DeviceMatch any() {
        return new DeviceMatch(List.of(), List.of());
    }

    /** Whether this rule names no device and no country. */
    public boolean empty() {
        return devices.isEmpty() && countries.isEmpty();
    }

    /**
     * @param device the visitor's classified device
     * @param country the visitor's country, or null/blank when the GeoIP reader had none
     */
    public boolean passes(DeviceClass device, String country) {
        if (empty()) {
            return true;
        }
        if (!devices.isEmpty() && (device == null || !devices.contains(device))) {
            return false;
        }
        if (!countries.isEmpty()) {
            String code = country == null ? "" : country.strip().toUpperCase(Locale.ROOT);
            return countries.contains(code);
        }
        return true;
    }

    /** The set a caller may name, so a request is validated against the same enum the classifier returns. */
    public static Set<String> deviceNames() {
        return java.util.Arrays.stream(DeviceClass.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet());
    }
}
