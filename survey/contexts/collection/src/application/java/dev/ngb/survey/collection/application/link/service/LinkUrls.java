package dev.ngb.survey.collection.application.link.service;

import dev.ngb.survey.collection.domain.link.LinkTokens;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds the URL a Tenant hands to a respondent from a link id, so a link's address is always re-derivable.
 *
 * <p>The token is signed with the app's keys and the base comes from configuration, which is why a Tenant reads a
 * link's URL back rather than storing it: rotate the base and the same link answers at the new host.
 */
public class LinkUrls {

    private final String publicBaseUrl;
    private final LinkTokens.Keys keys;

    public LinkUrls(String publicBaseUrl, LinkTokens.Keys keys) {
        this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
        this.keys = keys;
    }

    /** The URL of one link: {@code <base>/p/l/<token>/open}. */
    public String open(java.util.UUID linkId) {
        String token = LinkTokens.sign(linkId, keys);
        return publicBaseUrl + "/p/l/" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "/open";
    }

    private static String trimTrailingSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
