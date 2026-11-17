package dev.ngb.workflow.triggers.application.trigger.service;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.workflow.triggers.application.trigger.port.CronCalendar;
import dev.ngb.workflow.triggers.domain.trigger.TriggerKind;
import java.util.regex.Pattern;

/** The rules a trigger's fields follow. */
public class TriggerRules {

    private static final Pattern EXTERNAL_SOURCE = Pattern.compile("^[a-z][a-z0-9_-]{1,31}$");

    private static final int MAX_NAME = 200;
    private static final int MAX_EVENT_TYPE = 200;

    public String name(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new BadRequestException("a trigger name is 1 to " + MAX_NAME + " characters");
        }
        return name;
    }

    public TriggerKind kind(String raw) {
        try {
            return TriggerKind.valueOf(raw == null ? "" : raw.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("a trigger kind is APP, EXTERNAL, MANUAL or SCHEDULE");
        }
    }

    /**
     * The source of a trigger. An APP trigger names a suite app whose header set the verifier knows; an EXTERNAL one
     * names the system the Tenant chose; MANUAL and SCHEDULE need no source because nothing signs them.
     */
    public String source(TriggerKind kind, String raw) {
        String source = raw == null ? "" : raw.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (kind) {
            case APP -> {
                if (!WebhookSignatures.knownSource(source)) {
                    throw new BadRequestException(
                            "an APP trigger names a suite app: billing, survey, customer, loyalty or mailroom");
                }
                yield source;
            }
            case EXTERNAL -> {
                if (!EXTERNAL_SOURCE.matcher(source).matches()) {
                    throw new BadRequestException(
                            "an EXTERNAL trigger names its system with lower-case letters, digits, '_' or '-'");
                }
                yield source;
            }
            case MANUAL -> "manual";
            case SCHEDULE -> "schedule";
        };
    }

    public String eventType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String eventType = raw.strip();
        if (eventType.length() > MAX_EVENT_TYPE) {
            throw new BadRequestException("an event type is at most " + MAX_EVENT_TYPE + " characters");
        }
        return eventType;
    }

    public void schedule(String cron, String timezone, CronCalendar calendar) {
        String expression = cron == null ? "" : cron.strip();
        String zone = timezone == null || timezone.isBlank() ? "UTC" : timezone.strip();
        if (!calendar.valid(expression, zone)) {
            throw new BadRequestException("a schedule names a readable cron expression and a time zone");
        }
    }
}
