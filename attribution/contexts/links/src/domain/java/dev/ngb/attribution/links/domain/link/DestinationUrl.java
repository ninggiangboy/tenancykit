package dev.ngb.attribution.links.domain.link;

import dev.ngb.core.error.BadRequestException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A destination a redirect may send a visitor to. The checks here are the only guard against turning the suite's domain
 * into a phishing relay, so they run when a link is saved and again when a destination is edited.
 *
 * <p>The rules: an absolute {@code http} or {@code https} URL of at most 2,048 characters, no credentials in it, a host
 * that is not {@code localhost}, not an IP literal in a private, loopback, link-local or otherwise non-public range, and
 * not the suite's own base domain or any Tenant host. There is deliberately no DNS lookup and no fetch: a destination
 * that resolves today may resolve elsewhere tomorrow, and a save must not depend on the network.
 */
public record DestinationUrl(String value) {

    public static final int MAX_LENGTH = 2048;

    /** Hosts that name the machine itself, in the forms a URL parser accepts. */
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "localhost.localdomain", "ip6-localhost",
            "ip6-loopback", "metadata.google.internal");

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    public DestinationUrl {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("A destination URL is required.");
        }
        value = value.strip();
        if (value.length() > MAX_LENGTH) {
            throw new BadRequestException("A destination URL is at most " + MAX_LENGTH + " characters.");
        }
        URI uri = parse(value);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BadRequestException("A destination URL uses http or https only.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new BadRequestException("A destination URL carries no credentials.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BadRequestException("A destination URL needs a host.");
        }
        if (isLocalHost(host)) {
            throw new BadRequestException("A destination URL may not point at the local machine.");
        }
        if (isNonPublicLiteral(host)) {
            throw new BadRequestException("A destination URL may not point at a private or reserved address.");
        }
    }

    /**
     * Validate a destination against the suite's own hosts.
     *
     * @param raw the URL as the caller sent it
     * @param forbiddenHosts the suite's base domain and every Tenant host, each compared case-insensitively as a
     *     suffix, so {@code suite.example} and {@code acme.suite.example} are both refused
     */
    public static DestinationUrl forSuite(String raw, List<String> forbiddenHosts) {
        DestinationUrl url = new DestinationUrl(raw);
        String host = url.host().toLowerCase(Locale.ROOT);
        for (String forbidden : forbiddenHosts) {
            if (forbidden == null || forbidden.isBlank()) {
                continue;
            }
            String suffix = forbidden.toLowerCase(Locale.ROOT).strip();
            if (host.equals(suffix) || host.endsWith("." + suffix)) {
                throw new BadRequestException("A destination URL may not point at the suite's own domain.");
            }
        }
        return url;
    }

    /** The URL's host, lower-cased, without a port. */
    public String host() {
        return parse(value).getHost().toLowerCase(Locale.ROOT);
    }

    /** The URL's path and query, or empty when it carries none. */
    public String pathAndQuery() {
        URI uri = parse(value);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return path + query;
    }

    /** Whether the URL's query already names a parameter, so a click parameter would collide with it. */
    public boolean queryHas(String parameter) {
        URI uri = parse(value);
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return false;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            if (name.equals(parameter)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return value;
    }

    private static URI parse(String value) {
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute()) {
                throw new BadRequestException("A destination URL must be absolute.");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new BadRequestException("A destination URL is not a valid URL.");
        }
    }

    private static boolean isLocalHost(String host) {
        return LOCAL_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    private static boolean isNonPublicLiteral(String host) {
        // The URI parser keeps the brackets on an IPv6 literal but strips them from getHost in some JDKs; handle both.
        String bare = host;
        if (bare.startsWith("[") && bare.endsWith("]")) {
            bare = bare.substring(1, bare.length() - 1);
        }
        if (!IPV4.matcher(bare).matches() && !bare.contains(":")) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(bare);
            return !dev.ngb.core.webhook.DestinationPolicy.isPublic(address);
        } catch (UnknownHostException e) {
            // An unparseable literal is refused rather than sent.
            return true;
        }
    }
}
