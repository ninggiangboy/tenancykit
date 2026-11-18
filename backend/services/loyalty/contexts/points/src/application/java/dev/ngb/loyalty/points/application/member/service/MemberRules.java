package dev.ngb.loyalty.points.application.member.service;

import dev.ngb.loyalty.points.application.earn.exception.PointsRefusal;

/**
 * The rules a member's reference and profile obey.
 *
 * <p>The reference is the Tenant's own key for the member: it is trimmed, it is 1 to 128 characters, and it never
 * changes. A blank or oversized one is refused rather than corrected, because a caller that sends either has a bug the
 * refusal reports.
 */
public final class MemberRules {

    public static final int MAX_REF = 128;
    public static final int MAX_DISPLAY_NAME = 255;

    private MemberRules() {}

    /** @return the reference as stored: trimmed, 1 to 128 characters */
    public static String externalRef(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            throw PointsRefusal.invalid("The member's externalRef is required and must not be blank.");
        }
        if (value.length() > MAX_REF) {
            throw PointsRefusal.invalid("The member's externalRef has at most " + MAX_REF + " characters.");
        }
        return value;
    }

    /** @return the display name as stored, which may be absent */
    public static String displayName(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_DISPLAY_NAME) {
            throw PointsRefusal.invalid("The member's displayName has at most " + MAX_DISPLAY_NAME + " characters.");
        }
        return value;
    }

    /** @return a reference a path carries: trimmed, 1 to 128 characters */
    public static String reference(String raw, String what) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            throw PointsRefusal.invalid("The " + what + " is required and must not be blank.");
        }
        if (value.length() > MAX_REF) {
            throw PointsRefusal.invalid("The " + what + " has at most " + MAX_REF + " characters.");
        }
        return value;
    }
}
