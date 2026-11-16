package dev.ngb.core.visitor;

import java.util.List;
import java.util.Locale;

/**
 * Turns a user agent string into a bounded, non-identifying fact.
 *
 * <p>The raw string is read and discarded: a caller gets a {@link DeviceClass}, a browser family, whether the request
 * is a bot and nothing that identifies a person or a version. An empty or missing user agent is a {@link
 * TrafficClass#BOT}, because a real browser always sends one.
 *
 * <p>The rule set is data in this class, ordered, and case-insensitive: the first matching rule wins. Crawlers and
 * link-preview fetchers come first, because a preview bot's string also contains a platform token (for example
 * Facebook's contains {@code FBAV}) that a naive device rule would read as mobile. Tablet rules precede mobile rules,
 * because an iPad string contains {@code Mobile} on some versions. Adding a family is one entry in {@link #RULES}.
 */
public final class UserAgentClassifier {

    /** A device class, a browser family and the traffic class one user agent names. */
    public record Classification(DeviceClass device, String browserFamily, TrafficClass traffic) {

        /** The same facts, kept for a page view or a goal event that carried no user agent of its own. */
        public Classification withTraffic(TrafficClass other) {
            return new Classification(device, browserFamily, other);
        }
    }

    /** One ordered rule: any of the tokens makes the agent match, and the fields say what it becomes. */
    private record Rule(List<String> tokens, DeviceClass device, String browser, TrafficClass traffic) {}

    /**
     * The bot tokens. They are checked before any device rule, so {@code facebookexternalhit} and {@code Slackbot}
     * classify as bots however their string otherwise reads. The list covers the crawlers, unfurlers, monitors and
     * headless clients a marketing link actually meets; a new one is one token.
     */
    private static final List<String> BOT_TOKENS = List.of(
            "bot", "crawler", "spider", "slurp", "facebookexternalhit", "facebookcatalog", "whatsapp",
            "telegrambot", "discordbot", "slackbot", "slack-imgproxy", "twitterbot", "linkedinbot", "pinterest",
            "embedly", "quora link preview", "outbrain", "vkshare", "skypeuripreview", "redditbot", "applebot",
            "bingpreview", "google-inspectiontool", "googleother", "adsbot", "mediapartners-google", "apis-google",
            "feedfetcher", "python-requests", "python-urllib", "curl/", "wget/", "java/", "okhttp", "go-http-client",
            "httpclient", "axios/", "node-fetch", "headlesschrome", "phantomjs", "lighthouse", "pagespeed",
            "pingdom", "uptimerobot", "statuscake", "gtmetrix", "ahrefs", "semrush", "mj12bot", "dotbot",
            "petalbot", "bytespider", "yandexbot", "baiduspider", "duckduckbot", "sogou", "exabot", "ia_archiver",
            "archive.org_bot", "commoncrawl", "nutch", "scrapy", "colly", "guzzlehttp", "libwww-perl");

    /** A small, closed list of browser families. No version, and no engine detail beyond the family a report shows. */
    private static final List<Rule> RULES = List.of(
            new Rule(List.of("edge/", "edg/", "edga/", "edgios/"), DeviceClass.UNKNOWN, "Edge", TrafficClass.HUMAN),
            new Rule(List.of("opr/", "opera"), DeviceClass.UNKNOWN, "Opera", TrafficClass.HUMAN),
            new Rule(List.of("samsungbrowser"), DeviceClass.UNKNOWN, "Samsung Internet", TrafficClass.HUMAN),
            new Rule(List.of("firefox/", "fxios/"), DeviceClass.UNKNOWN, "Firefox", TrafficClass.HUMAN),
            new Rule(List.of("crios/", "chrome/", "chromium/"), DeviceClass.UNKNOWN, "Chrome", TrafficClass.HUMAN),
            new Rule(List.of("safari/"), DeviceClass.UNKNOWN, "Safari", TrafficClass.HUMAN));

    private static final String UNKNOWN_BROWSER = "Unknown";

    private UserAgentClassifier() {}

    /**
     * Classify one user agent.
     *
     * @param userAgent the request's user agent, or null/blank when it sent none
     */
    public static Classification classify(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return new Classification(DeviceClass.UNKNOWN, UNKNOWN_BROWSER, TrafficClass.BOT);
        }
        String agent = userAgent.toLowerCase(Locale.ROOT);
        if (isBot(agent)) {
            // A bot's device class is still read, because a report may want to know a preview came from a phone.
            return new Classification(deviceClass(agent), browserFamily(agent), TrafficClass.BOT);
        }
        return new Classification(deviceClass(agent), browserFamily(agent), TrafficClass.HUMAN);
    }

    /** The device class alone, for a caller that only needs the destination rule. */
    public static DeviceClass deviceOf(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return DeviceClass.UNKNOWN;
        }
        return deviceClass(userAgent.toLowerCase(Locale.ROOT));
    }

    /** The traffic class alone: {@link TrafficClass#BOT} for a crawler, a preview fetcher or an empty agent. */
    public static TrafficClass trafficOf(String userAgent) {
        return classify(userAgent).traffic();
    }

    /** The browser family alone, or {@code Unknown}. */
    public static String browserOf(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN_BROWSER;
        }
        return browserFamily(userAgent.toLowerCase(Locale.ROOT));
    }

    private static boolean isBot(String agent) {
        return BOT_TOKENS.stream().anyMatch(agent::contains);
    }

    private static DeviceClass deviceClass(String agent) {
        if (isTablet(agent)) {
            return DeviceClass.TABLET;
        }
        if (isMobile(agent)) {
            return DeviceClass.MOBILE;
        }
        if (isDesktop(agent)) {
            return DeviceClass.DESKTOP;
        }
        return DeviceClass.UNKNOWN;
    }

    private static boolean isTablet(String agent) {
        return agent.contains("ipad")
                || (agent.contains("android") && !agent.contains("mobile"))
                || agent.contains("tablet")
                || agent.contains("kindle")
                || agent.contains("silk/")
                || agent.contains("playbook")
                || agent.contains("nexus 7")
                || agent.contains("nexus 9")
                || agent.contains("sm-t");
    }

    private static boolean isMobile(String agent) {
        return agent.contains("iphone")
                || agent.contains("ipod")
                || agent.contains("windows phone")
                || agent.contains("iemobile")
                || agent.contains("blackberry")
                || agent.contains("bb10")
                || agent.contains("opera mini")
                || agent.contains("mobile")
                || agent.contains("android");
    }

    private static boolean isDesktop(String agent) {
        return agent.contains("windows nt")
                || agent.contains("macintosh")
                || agent.contains("mac os x")
                || agent.contains("x11")
                || agent.contains("linux")
                || agent.contains("cros");
    }

    private static String browserFamily(String agent) {
        for (Rule rule : RULES) {
            if (rule.tokens().stream().anyMatch(agent::contains)) {
                return rule.browser();
            }
        }
        return UNKNOWN_BROWSER;
    }

    /** The bot token list, exposed read-only so a deployment can see which rules exist without reaching a constant. */
    public static List<String> botTokens() {
        return BOT_TOKENS;
    }
}
