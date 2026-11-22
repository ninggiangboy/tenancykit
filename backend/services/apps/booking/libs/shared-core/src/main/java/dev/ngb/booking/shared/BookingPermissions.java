package dev.ngb.booking.shared;

import java.util.Set;

/** The booking app's permission codes; a test asserts this set equals {@code contracts/permissions/booking.yaml}. */
public final class BookingPermissions {

    public static final String RESOURCE_READ = "booking:resource:read";
    public static final String RESOURCE_MANAGE = "booking:resource:manage";
    public static final String OFFERING_READ = "booking:offering:read";
    public static final String OFFERING_MANAGE = "booking:offering:manage";
    public static final String CALENDAR_READ = "booking:calendar:read";
    public static final String CALENDAR_MANAGE = "booking:calendar:manage";
    public static final String BOOKING_READ = "booking:booking:read";
    public static final String BOOKING_HOLD = "booking:booking:hold";
    public static final String BOOKING_CONFIRM = "booking:booking:confirm";
    public static final String BOOKING_MANAGE = "booking:booking:manage";
    public static final String SERIES_READ = "booking:series:read";
    public static final String SERIES_MANAGE = "booking:series:manage";
    public static final String WAITLIST_READ = "booking:waitlist:read";
    public static final String WAITLIST_MANAGE = "booking:waitlist:manage";
    public static final String WEBHOOK_READ = "booking:webhook:read";
    public static final String WEBHOOK_MANAGE = "booking:webhook:manage";
    public static final String TOKEN_ISSUE = "booking:token:issue";
    public static final String SETTINGS_READ = "booking:settings:read";
    public static final String SETTINGS_MANAGE = "booking:settings:manage";

    public static final Set<String> CODES = Set.of(
            RESOURCE_READ,
            RESOURCE_MANAGE,
            OFFERING_READ,
            OFFERING_MANAGE,
            CALENDAR_READ,
            CALENDAR_MANAGE,
            BOOKING_READ,
            BOOKING_HOLD,
            BOOKING_CONFIRM,
            BOOKING_MANAGE,
            SERIES_READ,
            SERIES_MANAGE,
            WAITLIST_READ,
            WAITLIST_MANAGE,
            WEBHOOK_READ,
            WEBHOOK_MANAGE,
            TOKEN_ISSUE,
            SETTINGS_READ,
            SETTINGS_MANAGE);

    private BookingPermissions() {}
}
