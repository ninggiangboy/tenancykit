package dev.ngb.mailroom.audience.domain.receiver;

import dev.ngb.core.error.BadRequestException;
import java.util.Locale;

/**
 * The lookup identity of an address: trimmed and lower-cased, so {@code A@Example.com} and {@code a@example.com} are
 * one receiver and one suppression. The original spelling is still stored for display.
 */
public final class NormalizedAddress {

    private static final int MAX = 320;

    private NormalizedAddress() {}

    public static String normalize(String address) {
        if (address == null || address.isBlank()) {
            throw new BadRequestException("an address is required");
        }
        String trimmed = address.trim();
        if (trimmed.length() > MAX) {
            throw new BadRequestException("an address is at most " + MAX + " characters");
        }
        if (trimmed.indexOf('@') <= 0 || trimmed.indexOf('@') == trimmed.length() - 1) {
            throw new BadRequestException("the address is not a usable email address");
        }
        if (trimmed.indexOf('\r') >= 0 || trimmed.indexOf('\n') >= 0) {
            throw new BadRequestException("an address may not contain a line break");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
