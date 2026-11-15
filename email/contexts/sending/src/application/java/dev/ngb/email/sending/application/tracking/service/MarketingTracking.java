package dev.ngb.email.sending.application.tracking.service;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.email.content.api.render.RenderedEmail;
import dev.ngb.email.sending.application.email.port.TrackingHook;
import dev.ngb.email.sending.application.tracking.port.EmailLinkRepository;
import dev.ngb.email.sending.application.tracking.port.TrackingTokens;
import dev.ngb.email.sending.domain.tracking.EmailLink;
import dev.ngb.email.sending.domain.tracking.TrackingAction;
import dev.ngb.email.shared.EmailKind;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds the marketing affordances: the open pixel, rewritten click links and the one-click unsubscribe header.
 *
 * <p>A transactional email is left untouched. A marketing email's {@code http(s)} {@code href} is replaced with a
 * signed per-link URL and recorded here, so the click endpoint can only redirect to a URL this render produced. The
 * unsubscribe URL is also exposed to the template as {@code {{unsubscribe_url}}} before rendering.
 *
 * <p>Two links are deliberately left alone even in marketing mail. The unsubscribe link must keep working after the
 * body is purged, and rewriting it would both count the unsubscribe as a click and turn the footer into "link
 * expired" once housekeeping removes {@code email_links}. A URL the link column cannot store is left alone too: an
 * untracked link still reaches its destination, while recording it would abort the whole publishing tick.
 */
public class MarketingTracking implements TrackingHook {

    private static final Pattern HREF = Pattern.compile("(?i)(href\\s*=\\s*)([\"'])(https?://[^\"'<>]+)\\2");
    private static final Pattern BODY_END = Pattern.compile("(?i)</body\\s*>");
    private static final String PIXEL =
            "<img src=\"%s\" width=\"1\" height=\"1\" alt=\"\" style=\"display:none\" />";

    /** The same bound the {@code email_links.url} check enforces. */
    private static final int MAX_URL = 4096;

    private final TrackingTokens tokens;
    private final TrackingSettings settings;
    private final EmailLinkRepository links;
    private final IdGenerator ids;
    private final Clock clock;

    public MarketingTracking(
            TrackingTokens tokens,
            TrackingSettings settings,
            EmailLinkRepository links,
            IdGenerator ids,
            Clock clock) {
        this.tokens = tokens;
        this.settings = settings;
        this.links = links;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public Map<String, Object> systemVariables(EmailKind kind, UUID emailId, TenantId tenantId) {
        // The URL is available to every template; only marketing mail additionally rewrites links and adds a pixel.
        return Map.of("unsubscribe_url", unsubscribeUrl(tenantId, emailId));
    }

    @Override
    public Tracked prepare(RenderedEmail rendered, EmailKind kind, UUID emailId, TenantId tenantId) {
        if (!kind.marketing()) {
            return new Tracked(rendered.html(), Map.of(), List.of());
        }
        String unsubscribeUrl = unsubscribeUrl(tenantId, emailId);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("List-Unsubscribe", "<" + unsubscribeUrl + ">");
        headers.put("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        if (rendered.html() == null) {
            // A text-only marketing email has no links or pixel to add; the unsubscribe headers still apply.
            return new Tracked(null, headers, List.of());
        }
        List<Link> recorded = new ArrayList<>();
        Matcher matcher = HREF.matcher(rendered.html());
        StringBuilder rewritten = new StringBuilder();
        int position = 0;
        while (matcher.find()) {
            String destination = unescape(matcher.group(3));
            // The unsubscribe link is not a click target: rewriting it would count an unsubscribe as a click and break
            // the footer once housekeeping purges email_links.
            if (destination.equals(unsubscribeUrl) || !trackable(destination)) {
                continue;
            }
            String token = tokens.sign(tenantId, emailId, position, TrackingAction.CLICK);
            String replacement = matcher.group(1)
                    + matcher.group(2)
                    + settings.publicBaseUrl()
                    + "/t/c/"
                    + token
                    + matcher.group(2);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
            recorded.add(new Link(position, destination));
            position++;
        }
        matcher.appendTail(rewritten);

        String html = withPixel(rewritten.toString(), tenantId, emailId);
        return new Tracked(html, headers, recorded);
    }

    @Override
    public void recordLink(Link link, UUID emailId, TenantId tenantId, Instant now) {
        links.insert(new EmailLink(
                ids.newId(), tenantId, emailId, link.position(), link.url(), now));
    }

    private String withPixel(String html, TenantId tenantId, UUID emailId) {
        String pixel = PIXEL.formatted(
                settings.publicBaseUrl() + "/t/o/"
                        + tokens.sign(tenantId, emailId, 0, TrackingAction.OPEN) + ".gif");
        Matcher bodyEnd = BODY_END.matcher(html);
        if (bodyEnd.find()) {
            return html.substring(0, bodyEnd.start()) + pixel + html.substring(bodyEnd.start());
        }
        return html + pixel;
    }

    private String unsubscribeUrl(TenantId tenantId, UUID emailId) {
        return settings.publicBaseUrl()
                + "/t/u/"
                + tokens.sign(tenantId, emailId, 0, TrackingAction.UNSUBSCRIBE);
    }

    /**
     * Whether the link column can store this destination and the click endpoint can answer with it. A link that fails
     * either is left in place untracked rather than aborting the send.
     */
    private static boolean trackable(String url) {
        if (url.length() > MAX_URL) {
            return false;
        }
        try {
            URI.create(url);
            return true;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /**
     * Undoes the HTML escaping the renderer applied to an attribute value, because the recorded URL is a destination
     * rather than markup: storing {@code &amp;} would redirect to {@code ...?a=1&amp;b=2}, whose query has an
     * {@code amp;b} key. Mustache escapes only {@code & < > " ' =}, so this covers exactly that set.
     */
    static String unescape(String value) {
        if (value.indexOf('&') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int cursor = 0;
        while (cursor < value.length()) {
            int ampersand = value.indexOf('&', cursor);
            if (ampersand < 0) {
                out.append(value, cursor, value.length());
                break;
            }
            out.append(value, cursor, ampersand);
            int semicolon = value.indexOf(';', ampersand + 1);
            String decoded = semicolon < 0 ? null : decode(value.substring(ampersand + 1, semicolon));
            if (decoded == null) {
                out.append('&');
                cursor = ampersand + 1;
            } else {
                out.append(decoded);
                cursor = semicolon + 1;
            }
        }
        return out.toString();
    }

    private static String decode(String entity) {
        return switch (entity) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos", "#39" -> "'";
            case "equals", "#61" -> "=";
            default -> null;
        };
    }
}
