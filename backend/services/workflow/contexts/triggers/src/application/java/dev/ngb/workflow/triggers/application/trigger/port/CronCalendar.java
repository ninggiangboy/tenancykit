package dev.ngb.workflow.triggers.application.trigger.port;

import java.time.Instant;

/** Turns a cron expression and a zone into the first tick after an instant. */
public interface CronCalendar {

    Instant next(String cron, String timezone, Instant after);

    /** Whether the expression and the zone are readable, so a definition can be refused before it is stored. */
    boolean valid(String cron, String timezone);
}
