package dev.ngb.loyalty.program.application.program.service;

import dev.ngb.loyalty.program.application.program.exception.ProgramRefusal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * The rules a program obeys, shared by the create and the replace path.
 *
 * <p>A missing date rule takes its default, because the API document declares one for each of them: an omitted
 * {@code expiryMonths} is 12, not 0. Everything else is refused rather than corrected.
 */
public final class ProgramRules {

    public static final int DEFAULT_EXPIRY_MONTHS = 12;
    public static final int DEFAULT_EXPIRY_NOTICE_DAYS = 30;
    public static final int DEFAULT_TIER_WINDOW_MONTHS = 12;
    public static final String DEFAULT_TIME_ZONE = "UTC";

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private ProgramRules() {}

    /** @return the ISO 4217 code, which is three upper-case letters */
    public static String currency(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (!CURRENCY.matcher(value).matches()) {
            throw ProgramRefusal.invalid("currency is an ISO 4217 code of three upper-case letters, such as USD");
        }
        return value;
    }

    /** @return minor units of the currency that earn one point, at least 1 */
    public static long unitAmount(Long raw) {
        if (raw == null) {
            throw ProgramRefusal.invalid("currencyUnit is required and is the minor units that earn one point");
        }
        if (raw < 1) {
            throw ProgramRefusal.invalid("currencyUnit must be at least 1");
        }
        return raw;
    }

    public static int expiryMonths(Integer raw) {
        int value = raw == null ? DEFAULT_EXPIRY_MONTHS : raw;
        if (value < 1 || value > 120) {
            throw ProgramRefusal.invalid("expiryMonths must be within 1-120");
        }
        return value;
    }

    /** @return days of advance notice, 0 turning the notice off */
    public static int expiryNoticeDays(Integer raw) {
        int value = raw == null ? DEFAULT_EXPIRY_NOTICE_DAYS : raw;
        if (value < 0 || value > 365) {
            throw ProgramRefusal.invalid("expiryNoticeDays must be within 0-365");
        }
        return value;
    }

    public static int tierWindowMonths(Integer raw) {
        int value = raw == null ? DEFAULT_TIER_WINDOW_MONTHS : raw;
        if (value < 1 || value > 60) {
            throw ProgramRefusal.invalid("tierWindowMonths must be within 1-60");
        }
        return value;
    }

    /** @return the IANA zone the promotion rules are evaluated in, {@code UTC} when the request names none */
    public static ZoneId timeZone(String raw) {
        String value = raw == null || raw.isBlank() ? DEFAULT_TIME_ZONE : raw.strip();
        try {
            return ZoneId.of(value);
        } catch (DateTimeException e) {
            throw ProgramRefusal.invalid("timeZone must be an IANA zone id such as Europe/Paris");
        }
    }
}
