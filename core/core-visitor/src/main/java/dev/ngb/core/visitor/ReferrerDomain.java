package dev.ngb.core.visitor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import org.apache.hc.client5.http.psl.PublicSuffixMatcher;
import org.apache.hc.client5.http.psl.PublicSuffixMatcherLoader;

/**
 * Reduces a referrer to its registrable domain: no scheme, no path, no query, no port.
 *
 * <p>The public-suffix list decides where the registrable part ends, so {@code https://www.bbc.co.uk/news?x=1} gives
 * {@code bbc.co.uk} and {@code shop.example.com.vn} gives {@code example.com.vn}. A referrer that is not a URL, or a
 * host that is a bare public suffix, an IP literal or a single-label name such as {@code localhost}, yields empty: a
 * report shows the domain or nothing, and the path a person was on is never stored.
 */
public final class ReferrerDomain {

    /** The stored form's limit; a registrable domain is far shorter, and this bounds a hostile header. */
    public static final int MAX_LENGTH = 253;

    private static final PublicSuffixMatcher SUFFIXES = PublicSuffixMatcherLoader.getDefault();

    private ReferrerDomain() {}

    /**
     * @param referrer the {@code Referer} header value, or null
     * @return the registrable domain in lower case, or empty when the referrer names none
     */
    public static Optional<String> of(String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return Optional.empty();
        }
        String text = referrer.strip();
        // A hostile header is capped before it reaches the URL parser; no real referrer is this long.
        if (text.length() > 4096) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        return registrableDomain(host);
    }

    /**
     * The registrable domain of a bare host, or empty for an IP literal, a single-label name or a bare public suffix.
     *
     * <p>{@code getDomainRoot} answers null for a bare suffix ({@code co.uk}, {@code com.vn}) and, unhelpfully, a
     * truncated value for an IPv4 literal ({@code 192.168.1.1} becomes {@code 1.1}), so the literal is refused before
     * the lookup.
     */
    public static Optional<String> registrableDomain(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") || isIpLiteral(normalized) || !normalized.contains(".")) {
            return Optional.empty();
        }
        String root = SUFFIXES.getDomainRoot(normalized);
        if (root == null || root.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(root);
    }

    private static boolean isIpLiteral(String host) {
        if (host.contains(":")) {
            return true;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                return false;
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }
}
