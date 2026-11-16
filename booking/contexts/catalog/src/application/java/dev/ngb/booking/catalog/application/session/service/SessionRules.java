package dev.ngb.booking.catalog.application.session.service;

import dev.ngb.booking.catalog.application.catalog.exception.CatalogValidationException;
import dev.ngb.booking.catalog.application.offering.query.OfferingReadRepository;
import dev.ngb.booking.catalog.application.offering.query.OfferingView;
import dev.ngb.booking.catalog.domain.offering.OfferingKind;
import dev.ngb.booking.catalog.domain.session.Pool;
import dev.ngb.booking.catalog.domain.session.PoolCode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The rules a session series body must satisfy: the offering must be one that sells places, the zone must be one the
 * JDK knows, the local start must be a wall-clock date-time, and the pools must fit inside the capacity the series
 * offers.
 */
public final class SessionRules {

    private static final DateTimeFormatter BASIC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT);

    private final OfferingReadRepository offerings;

    public SessionRules(OfferingReadRepository offerings) {
        this.offerings = offerings;
    }

    /**
     * @return the offering the series sells places of
     * @throws CatalogValidationException when the offering does not exist here or is not a {@code CAPACITY} one
     */
    public OfferingView requireCapacityOffering(UUID offeringId) {
        if (offeringId == null) {
            throw new CatalogValidationException("offeringId is required");
        }
        OfferingView offering = offerings
                .findById(offeringId)
                .orElseThrow(() -> new CatalogValidationException("no offering exists here with that id"));
        if (offering.kind() != OfferingKind.CAPACITY) {
            throw new CatalogValidationException("a session series needs a CAPACITY offering");
        }
        return offering;
    }

    /** @return the IANA zone the local start is read in */
    public static String zone(String raw) {
        String zone = raw == null ? null : raw.strip();
        if (zone == null || zone.isEmpty()) {
            throw new CatalogValidationException("zone is required");
        }
        try {
            ZoneId.of(zone);
        } catch (DateTimeException unknown) {
            throw new CatalogValidationException("the zone " + zone + " is not one the JDK knows");
        }
        return zone;
    }

    /** @return the first local start, wall-clock and without an offset */
    public static String dtstart(String raw) {
        String dtstart = raw == null ? null : raw.strip();
        if (dtstart == null || dtstart.isEmpty()) {
            throw new CatalogValidationException("dtstart is required");
        }
        try {
            LocalDateTime.parse(dtstart);
        } catch (DateTimeException notLocal) {
            throw new CatalogValidationException("dtstart is a local date-time, such as 2026-01-05T09:00:00");
        }
        return dtstart;
    }

    /** @return a number that must be present and at least one */
    public static int requiredPositive(Integer value, String field) {
        if (value == null) {
            throw new CatalogValidationException(field + " is required");
        }
        if (value < 1) {
            throw new CatalogValidationException(field + " must be at least 1");
        }
        return value;
    }

    /** @return the recurrence lines, trimmed, which are at least one and never blank */
    public static List<String> lines(List<String> raw) {
        List<String> lines = new ArrayList<>();
        if (raw != null) {
            for (String line : raw) {
                String text = line == null ? null : line.strip();
                if (text == null || text.isEmpty()) {
                    throw new CatalogValidationException("a recurrence line is not blank");
                }
                lines.add(text);
            }
        }
        if (lines.isEmpty()) {
            throw new CatalogValidationException("rule carries at least one line");
        }
        return List.copyOf(lines);
    }

    /**
     * One pool as a caller sent it.
     *
     * @throws CatalogValidationException when the code is empty or longer than 60 characters, or the capacity is
     *     negative
     */
    public static Pool pool(String code, Integer capacity) {
        String value = code == null ? null : code.strip();
        if (value == null || value.isEmpty() || value.length() > 60) {
            throw new CatalogValidationException("a pool code is between 1 and 60 characters");
        }
        if (capacity == null || capacity < 0) {
            throw new CatalogValidationException("a pool's capacity is at least 0");
        }
        return new Pool(new PoolCode(value), capacity);
    }

    /**
     * The pools a session offers places from: the ones the caller named, or one {@code default} pool holding the whole
     * capacity when it named none.
     *
     * @throws CatalogValidationException when a code is empty, is named twice, or the pools add up to more places than
     *     the session offers
     */
    public static List<Pool> pools(List<Pool> raw, int capacity) {
        if (raw == null || raw.isEmpty()) {
            return List.of(new Pool(PoolCode.defaultPool(), capacity));
        }
        List<Pool> pools = new ArrayList<>(raw);
        Set<String> codes = new HashSet<>();
        int total = 0;
        for (Pool pool : pools) {
            if (!codes.add(pool.code().value())) {
                throw new CatalogValidationException("the pool " + pool.code() + " is named twice");
            }
            total += pool.capacity();
        }
        if (total > capacity) {
            throw new CatalogValidationException(
                    "the pools offer " + total + " places, more than the session's " + capacity);
        }
        pools.sort(Comparator.comparing((Pool pool) -> pool.code().value()));
        return List.copyOf(pools);
    }

    /** The exclusion a cancelled occurrence appends, so the next expansion does not recreate it. */
    public static String exclusion(String zone, Instant startsAt) {
        LocalDateTime local = LocalDateTime.ofInstant(startsAt, ZoneId.of(zone));
        return "EXDATE;TZID=" + zone + ":" + BASIC.format(local);
    }
}
