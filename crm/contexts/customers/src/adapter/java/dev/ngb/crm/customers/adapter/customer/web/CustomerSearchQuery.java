package dev.ngb.crm.customers.adapter.customer.web;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.QueryParser;
import dev.ngb.crm.customers.application.customer.query.CustomerListing;
import dev.ngb.crm.customers.application.customer.query.CustomerSearchCriteria;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The customer list's query string, read once: the list contract's own paging and sort, and the search terms the list
 * and the export share.
 *
 * <p>A parameter the list does not know is refused rather than ignored, because a silently dropped filter returns more
 * customers than the caller asked for — and for an export that means writing customers the caller did not ask for into
 * a file it may forward.
 */
final class CustomerSearchQuery {

    private CustomerSearchQuery() {}

    /**
     * One request's filters.
     *
     * @param listing the paging, the sort and the list contract's own filters
     * @param criteria the search term, tags, field values, consent states and creation window
     */
    record Parsed(ListQuery listing, CustomerSearchCriteria criteria) {}

    /**
     * @param params the request's parameters, with every repeated one kept
     * @param passthrough parameters the caller of this method reads itself, such as an export's page size
     */
    static Parsed parse(Map<String, List<String>> params, String... passthrough) {
        List<String> own = List.of(passthrough);
        Map<String, List<String>> listing = new LinkedHashMap<>();
        List<String> tags = new ArrayList<>();
        Map<String, String> equals = new LinkedHashMap<>();
        Map<String, String> atLeast = new LinkedHashMap<>();
        Map<String, String> atMost = new LinkedHashMap<>();
        Map<String, String> consents = new LinkedHashMap<>();
        String q = null;
        Instant createdFrom = null;
        Instant createdTo = null;
        for (Map.Entry<String, List<String>> param : params.entrySet()) {
            String name = param.getKey();
            if (own.contains(name)) {
                continue;
            }
            switch (name) {
                case "q" -> q = single(name, param.getValue());
                case "tag" -> tags.addAll(param.getValue());
                case "createdFrom" -> createdFrom = instant(name, single(name, param.getValue()));
                case "createdTo" -> createdTo = instant(name, single(name, param.getValue()));
                default -> {
                    if (name.startsWith("field.")) {
                        field(name, single(name, param.getValue()), equals, atLeast, atMost);
                    } else if (name.startsWith("consent.")) {
                        consents.put(key(name, "consent.", ""), single(name, param.getValue()));
                    } else {
                        listing.put(name, param.getValue());
                    }
                }
            }
        }
        ListQuery query = QueryParser.parse(CustomerListing.SCHEMA, listing);
        return new Parsed(
                query,
                new CustomerSearchCriteria(q, tags, equals, atLeast, atMost, consents, createdFrom, createdTo));
    }

    /** One {@code field.} parameter: equality, a lower bound or an upper bound on one of the Tenant's own fields. */
    private static void field(
            String name, String value, Map<String, String> equals, Map<String, String> atLeast, Map<String, String> atMost) {
        if (name.endsWith(".gte")) {
            atLeast.put(key(name, "field.", ".gte"), value);
        } else if (name.endsWith(".lte")) {
            atMost.put(key(name, "field.", ".lte"), value);
        } else {
            equals.put(key(name, "field.", ""), value);
        }
    }

    private static String key(String name, String prefix, String suffix) {
        String key = name.substring(prefix.length());
        if (!suffix.isEmpty()) {
            key = key.substring(0, key.length() - suffix.length());
        }
        if (key.isEmpty() || key.indexOf('.') >= 0) {
            throw new BadRequestException("The parameter " + name + " does not name one field key.");
        }
        return key;
    }

    private static String single(String name, List<String> values) {
        if (values.isEmpty() || values.get(0) == null) {
            throw new BadRequestException("The parameter " + name + " takes a value.");
        }
        return values.get(0);
    }

    private static Instant instant(String name, String raw) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException notAnInstant) {
            throw new BadRequestException("The parameter " + name + " takes an ISO-8601 instant.");
        }
    }
}
