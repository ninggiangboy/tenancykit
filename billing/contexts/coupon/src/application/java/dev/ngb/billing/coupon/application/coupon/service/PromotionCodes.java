package dev.ngb.billing.coupon.application.coupon.service;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Generates a promotion code a customer can read and type. The alphabet omits the characters that are misread on a
 * poster or a phone (0/O, 1/I/L), so a code survives being copied by hand. Twenty characters from that alphabet is far
 * past guessing, which is why a guessed code is not a realistic path to a free discount.
 */
public final class PromotionCodes {

    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int LENGTH = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PromotionCodes() {}

    /** A fresh code, never null and never blank. */
    public static String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return code.toString();
    }

    /** The form a code is stored and compared under, so a customer may type it in any case. */
    public static String normalize(String code) {
        return code.strip().toUpperCase(Locale.ROOT);
    }
}
