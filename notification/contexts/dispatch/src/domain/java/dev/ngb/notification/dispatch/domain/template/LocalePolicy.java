package dev.ngb.notification.dispatch.domain.template;

import java.util.Locale;
import java.util.Set;

/**
 * The locale a message is written in: the user's preference, else the tenant's default, else {@code en}.
 *
 * <p>It is a pure function of two nullable tags and needs no port: a service call or a table for this would be an
 * abstraction with no second implementation. An unsupported tag falls through rather than throwing, because a bad
 * preference must never block a notification.
 */
public final class LocalePolicy {

    public static final String FALLBACK = "en";

    /** The locales a default template ships in; an override row may carry only these. */
    public static final Set<String> SUPPORTED = Set.of("en", "vi");

    private LocalePolicy() {}

    public static String choose(String userLocale, String tenantLocale) {
        return firstSupported(userLocale, tenantLocale);
    }

    private static String firstSupported(String... candidates) {
        for (String candidate : candidates) {
            String tag = normalise(candidate);
            if (tag != null && SUPPORTED.contains(tag)) {
                return tag;
            }
        }
        return FALLBACK;
    }

    private static String normalise(String locale) {
        if (locale == null || locale.isBlank()) {
            return null;
        }
        String tag = locale.trim().toLowerCase(Locale.ROOT);
        int separator = tag.indexOf('-');
        return separator < 0 ? tag : tag.substring(0, separator);
    }
}
