package dev.ngb.attribution.links.domain.redirect;

import dev.ngb.attribution.links.domain.link.DestinationUrl;
import dev.ngb.core.error.BadRequestException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the {@code Location} a redirect answers with: the chosen destination, the link's UTM values merged over the
 * ones already in its query, and the signed click id appended under the tenant's own parameter name.
 *
 * <p>A UTM value the link holds wins over one already on the destination, so a campaign's tagging is what a report
 * sees. Existing parameters of any other name are kept as they arrived, and the fragment is preserved, so a deep link
 * still lands where the Tenant pointed it. The click parameter is appended last and always under one name; a
 * destination that already used that name is refused at save time, so this never overwrites a landing page's own value.
 */
public final class RedirectLocation {

    private RedirectLocation() {}

    /**
     * @param destination the destination the selector chose
     * @param utm the link's stored UTM values, bare ({@code source}) or prefixed ({@code utm_source})
     * @param clickParam the tenant's click parameter name
     * @param clickToken the signed click id
     */
    public static String build(
            DestinationUrl destination, Map<String, String> utm, String clickParam, String clickToken) {
        URI uri = parse(destination.value());
        Map<String, String> merged = new LinkedHashMap<>();
        for (String pair : pairs(uri.getRawQuery())) {
            merged.put(rawName(pair), pair);
        }
        utmNames(normalize(utm)).forEach(name -> merged.remove(name));
        normalize(utm).forEach((name, value) -> merged.put(name, name + "=" + encode(value)));
        if (clickToken != null && !clickToken.isBlank()) {
            // A HEAD answers where a visitor would go without naming a click, so no empty parameter is appended.
            merged.put(clickParam, clickParam + "=" + encode(clickToken));
        }
        StringBuilder out = new StringBuilder();
        out.append(uri.getScheme()).append("://").append(uri.getRawAuthority()).append(nullToEmpty(uri.getRawPath()));
        if (!merged.isEmpty()) {
            out.append('?').append(String.join("&", merged.values()));
        }
        if (uri.getRawFragment() != null) {
            out.append('#').append(uri.getRawFragment());
        }
        return out.toString();
    }

    /**
     * Normalizes a link's stored UTM map to the {@code utm_*} names a destination query uses.
     *
     * <p>A caller may have stored {@code source} or {@code utm_source}; both mean the same parameter, so a link created
     * under one spelling tags exactly like a link created under the other.
     */
    public static Map<String, String> normalize(Map<String, String> utm) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (utm == null) {
            return normalized;
        }
        utm.forEach((rawName, rawValue) -> {
            if (rawName == null || rawValue == null) {
                return;
            }
            String name = rawName.strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                return;
            }
            String parameter = name.startsWith("utm_") ? name : "utm_" + name;
            normalized.put(parameter, rawValue.strip());
        });
        return normalized;
    }

    private static List<String> utmNames(Map<String, String> normalized) {
        return new ArrayList<>(normalized.keySet());
    }

    private static List<String> pairs(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return List.of();
        }
        List<String> pairs = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (!pair.isEmpty()) {
                pairs.add(pair);
            }
        }
        return pairs;
    }

    private static String rawName(String pair) {
        int equals = pair.indexOf('=');
        return equals < 0 ? pair : pair.substring(0, equals);
    }

    private static String encode(String value) {
        // A query value, not a form field: a space is %20 so a destination that distinguishes them still works.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            // A stored destination was validated at save time, so reaching this means the row was hand-edited.
            throw new BadRequestException("A destination URL is not a valid URL.");
        }
    }
}
