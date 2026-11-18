package dev.ngb.loyalty.rewards.domain.voucher;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * The code a voucher is presented by.
 *
 * <p>It is twelve characters of Crockford's base32 — the digits, the letters without {@code I}, {@code L}, {@code O} and
 * {@code U}, so a code read off a screen or a printout cannot be mistyped into another valid one. Sixty bits of random
 * make a guess useless against an authenticated endpoint, and the code is still only a token: the voucher's id is what a
 * Tenant derives its own keys from.
 */
public final class VoucherCode {

    /** How many characters a generated code holds. */
    public static final int LENGTH = 12;

    /** The longest code this context accepts, which is the column's bound and the API document's. */
    public static final int MAX_LENGTH = 100;

    /** Crockford's alphabet: the digits then the letters, without I, L, O and U. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private VoucherCode() {}

    /** @return a fresh random code, twelve characters of the alphabet above */
    public static String generated() {
        char[] code = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            code[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }

    /**
     * Reads a code a caller sent the way Crockford's alphabet says to: upper-cased, without the separators a person
     * adds when reading one out, and with the letters the alphabet leaves out mapped to the digit they look like.
     *
     * @return the code as stored
     */
    public static String canonical(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder value = new StringBuilder(raw.length());
        for (char character : raw.strip().toUpperCase(Locale.ROOT).toCharArray()) {
            switch (character) {
                case '-', ' ' -> {
                    // A separator is decoration, not part of the code.
                }
                case 'O' -> value.append('0');
                case 'I', 'L' -> value.append('1');
                default -> value.append(character);
            }
        }
        return value.toString();
    }
}
