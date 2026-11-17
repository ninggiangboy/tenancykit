package dev.ngb.customer.customers.application.tag.service;

import dev.ngb.customer.customers.application.customer.exception.InvalidCustomerException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What a tag must be before it is stored. A tag is the Tenant's own word for a group of customers, so it is stored
 * lower-cased and without surrounding space, and only its shape and its count are bounded.
 *
 * <p>Storing one tag once per customer makes the set a set: adding a tag a customer already carries changes nothing,
 * and removing one it does not carry changes nothing either.
 */
public final class TagRules {

    /** The longest tag a Tenant may choose, counting its first character. */
    public static final int MAX_TAG = 50;

    private static final Pattern TAG = Pattern.compile("^[a-z0-9][a-z0-9 _:-]{0,49}$");

    private TagRules() {}

    /** One tag, trimmed and lower-cased; the stored tag is the one a listing returns. */
    public static String tag(String raw) {
        if (raw == null) {
            throw new InvalidCustomerException("A tag is required.");
        }
        String tag = raw.strip().toLowerCase(Locale.ROOT);
        if (!TAG.matcher(tag).matches()) {
            throw new InvalidCustomerException("A tag is 1 to " + MAX_TAG
                    + " lower-case letters, digits, spaces, underscores, colons or dashes and starts with a letter or"
                    + " a digit.");
        }
        return tag;
    }

    /**
     * The whole tag set of a customer, in tag order.
     *
     * @throws InvalidCustomerException when the set holds more tags than one customer may carry
     */
    public static List<String> tags(Collection<String> raw, int maxPerCustomer) {
        TreeSet<String> tags = new TreeSet<>();
        if (raw != null) {
            for (String entry : raw) {
                tags.add(tag(entry));
            }
        }
        requireWithin(maxPerCustomer, tags.size());
        return List.copyOf(tags);
    }

    /** @throws InvalidCustomerException when a customer would hold more tags than it may */
    public static void requireWithin(int maxPerCustomer, int size) {
        if (size > maxPerCustomer) {
            throw new InvalidCustomerException(
                    "A customer carries at most " + maxPerCustomer + " tags.");
        }
    }

    /** The tags to add to a set, in tag order, without the ones it already holds. */
    public static List<String> additions(Collection<String> raw) {
        if (raw == null) {
            return List.of();
        }
        TreeSet<String> tags = new TreeSet<>();
        for (String entry : raw) {
            tags.add(tag(entry));
        }
        return new ArrayList<>(tags);
    }
}
