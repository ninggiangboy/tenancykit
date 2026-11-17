package dev.ngb.customer.activity.application.activity.service;

import dev.ngb.customer.activity.application.activity.command.RecordActivityUseCase;
import dev.ngb.customer.activity.application.activity.exception.ActivityPayloadTooLargeException;
import dev.ngb.customer.activity.application.activity.exception.InvalidActivityException;
import dev.ngb.customer.activity.application.activity.service.ActivityPayloads;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What an activity must be before it is appended: the contract's rules in one place, so the single call and a batch
 * item cannot disagree about what an acceptable activity is.
 *
 * <p>{@code type} is the writer's own vocabulary, so it is only bounded and made usable as a key — never enumerated.
 * The payload is an object of at most a setting's worth of bytes, because one activity also bounds one response and the
 * timeline it is read back in.
 */
public final class ActivityRules {

    /** The longest reference the Tenant may choose for a customer. */
    public static final int MAX_EXTERNAL_REF = 128;

    /** The longest source name, matching the column's own bound. */
    public static final int MAX_SOURCE = 64;

    /** The longest caller reference, matching the column's own bound. */
    public static final int MAX_ACTIVITY_REF = 200;

    /** The shape a type must have: a lower-case key the writer chose, so it can be filtered and compared. */
    private static final Pattern TYPE = Pattern.compile("^[a-z][a-z0-9._-]{0,63}$");

    private ActivityRules() {}

    /** An activity request checked against every rule, ready to be appended. */
    public record Planned(
            String externalRef,
            String type,
            Instant occurredAt,
            String source,
            Map<String, Object> payload,
            String activityRef) {}

    /** @throws InvalidActivityException when the activity is not acceptable */
    public static Planned plan(
            RecordActivityUseCase.Command command,
            Instant now,
            Duration maxFutureSkew,
            ActivityPayloads payloads,
            int maxPayloadBytes) {
        if (command == null) {
            throw new InvalidActivityException("An activity is required.");
        }
        String externalRef = reference(command.externalRef(), MAX_EXTERNAL_REF, "customer");
        String type = type(command.type());
        Instant occurredAt = occurredAt(command.occurredAt(), now, maxFutureSkew);
        String source = source(command.source());
        Map<String, Object> payload = payload(command.payload(), payloads, maxPayloadBytes);
        String activityRef = activityRef(command.activityRef());
        return new Planned(externalRef, type, occurredAt, source, payload, activityRef);
    }

    /** The Tenant's reference, trimmed: it names the customer the activity is about. */
    public static String reference(String raw, int max, String what) {
        if (raw == null) {
            throw new InvalidActivityException("A " + what + " reference is required.");
        }
        String ref = raw.strip();
        if (ref.isEmpty() || ref.length() > max) {
            throw new InvalidActivityException("A " + what + " reference is 1 to " + max + " characters.");
        }
        return ref;
    }

    public static String type(String raw) {
        String type = raw == null ? null : raw.strip();
        if (type == null || !TYPE.matcher(type).matches()) {
            throw new InvalidActivityException(
                    "A type is 1 to 64 characters matching ^[a-z][a-z0-9._-]{0,63}$.");
        }
        return type;
    }

    /** When it happened: at most a few minutes in the future, and otherwise unbounded for a Tenant's own history. */
    public static Instant occurredAt(Instant raw, Instant now, Duration maxFutureSkew) {
        if (raw == null) {
            throw new InvalidActivityException("An activity needs an occurredAt.");
        }
        if (raw.isAfter(now.plus(maxFutureSkew))) {
            throw new InvalidActivityException(
                    "An activity may be at most " + maxFutureSkew.toMinutes() + " minutes in the future.");
        }
        return raw;
    }

    /** A source the writer chose: absent, or bounded and stored as sent. */
    public static String source(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isBlank() || raw.length() > MAX_SOURCE) {
            throw new InvalidActivityException("A source is 1 to " + MAX_SOURCE + " characters.");
        }
        return raw;
    }

    /** A reference a caller chose for its own call: absent, or bounded and trimmed. */
    public static String activityRef(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String ref = raw.strip();
        if (ref.length() > MAX_ACTIVITY_REF) {
            throw new InvalidActivityException("An activityRef is at most " + MAX_ACTIVITY_REF + " characters.");
        }
        return ref;
    }

    /** The writer's payload: an absent one is an empty object, a present one must be an object within the bound. */
    private static Map<String, Object> payload(Object raw, ActivityPayloads payloads, int maxPayloadBytes) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> sent)) {
            throw new ActivityPayloadTooLargeException("A payload is a JSON object.");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : sent.entrySet()) {
            values.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        if (payloads.byteLength(values) > maxPayloadBytes) {
            throw new ActivityPayloadTooLargeException(
                    "A payload is at most " + maxPayloadBytes + " bytes.");
        }
        return Collections.unmodifiableMap(values);
    }
}
