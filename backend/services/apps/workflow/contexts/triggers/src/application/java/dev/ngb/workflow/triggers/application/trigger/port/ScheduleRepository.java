package dev.ngb.workflow.triggers.application.trigger.port;

import dev.ngb.workflow.triggers.domain.schedule.Schedule;
import java.time.Instant;
import java.util.List;

/** The schedule rows. A due row is claimed with {@code FOR UPDATE SKIP LOCKED}, so one tick fires once. */
public interface ScheduleRepository {

    void add(Schedule schedule);

    /** Locks at most {@code limit} schedules due at {@code now}, skipping rows another worker holds. */
    List<Schedule> lockDue(Instant now, int limit);

    void update(Schedule schedule);
}
