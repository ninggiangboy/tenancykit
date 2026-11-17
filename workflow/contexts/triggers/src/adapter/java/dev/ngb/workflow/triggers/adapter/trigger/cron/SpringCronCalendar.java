package dev.ngb.workflow.triggers.adapter.trigger.cron;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.workflow.triggers.application.trigger.port.CronCalendar;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;

/** Cron arithmetic through Spring's expression parser, behind the application's own port. */
public class SpringCronCalendar implements CronCalendar {

    @Override
    public Instant next(String cron, String timezone, Instant after) {
        ZoneId zone = zone(timezone);
        CronExpression expression = expression(cron);
        ZonedDateTime next = expression.next(ZonedDateTime.ofInstant(after, zone));
        if (next == null) {
            throw new BadRequestException("this cron expression has no future tick");
        }
        return next.toInstant();
    }

    @Override
    public boolean valid(String cron, String timezone) {
        try {
            expression(cron);
            zone(timezone);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static CronExpression expression(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new BadRequestException("a schedule names a cron expression");
        }
        try {
            return CronExpression.parse(cron.strip());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("a schedule names a readable cron expression");
        }
    }

    private static ZoneId zone(String timezone) {
        try {
            return timezone == null || timezone.isBlank() ? ZoneId.of("UTC") : ZoneId.of(timezone.strip());
        } catch (RuntimeException e) {
            throw new BadRequestException("a schedule names a readable time zone");
        }
    }
}
