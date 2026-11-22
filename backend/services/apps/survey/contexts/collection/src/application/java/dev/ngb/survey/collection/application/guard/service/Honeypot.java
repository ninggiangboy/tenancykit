package dev.ngb.survey.collection.application.guard.service;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The decoy field one visit's schema names, so a submit that fills it can be discarded without telling the bot.
 *
 * <p>The name is random per visit and never referenced by a real question, which is what makes it a honeypot: a
 * browser never renders it, so a value in it came from something reading the schema and filling every field. The
 * submit that carries it is answered as accepted, so a bot learns nothing from the refusal it never sees.
 */
public final class Honeypot {

    private static final String PREFIX = "hp_";

    private Honeypot() {}

    /** A fresh decoy name for one visit. */
    public static String nameFor(java.util.UUID visitId) {
        // Derived from the visit plus randomness, so two visits never share a name a bot could learn once.
        long noise = ThreadLocalRandom.current().nextLong();
        return PREFIX + Long.toUnsignedString(visitId.getMostSignificantBits() ^ noise, 36).toLowerCase(Locale.ROOT);
    }

    /** Whether a submitted answer set fills the decoy, which is what marks the submit as a bot's. */
    public static boolean filled(String name, java.util.Map<String, ?> answers) {
        if (name == null || answers == null) {
            return false;
        }
        Object value = answers.get(name);
        return value != null && !String.valueOf(value).isBlank();
    }
}
