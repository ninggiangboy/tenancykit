package dev.ngb.booking.boot.health;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The invariants the claim engines promise, checked against the stored rows.
 *
 * <p>Each query names the rows that break one promise; an empty result is the pass. The check reads only the current
 * tenant, so a platform operator runs the SQL as the owner (see {@code ops/smoke/booking-invariants.sh}) to cover every
 * tenant at once. A violation is a line, never a silent count.
 */
public class InvariantCheck {

    private final JdbcTemplate jdbc;
    private final long graceSeconds;
    private final long jobIntervalSeconds;

    public InvariantCheck(JdbcTemplate jdbc, long graceSeconds, long jobIntervalSeconds) {
        this.jdbc = jdbc;
        this.graceSeconds = graceSeconds;
        this.jobIntervalSeconds = jobIntervalSeconds;
    }

    public List<String> violations() {
        List<String> violations = new ArrayList<>();
        violations.addAll(jdbc.queryForList("""
                select 'capacity oversold: session ' || s.id
                from sessions s
                join capacity_units u on u.session_id = s.id and u.tenant_id = s.tenant_id
                where u.status in ('HELD', 'BOOKED')
                group by s.tenant_id, s.id, s.capacity
                having count(*) > s.capacity
                """, String.class));
        violations.addAll(jdbc.queryForList("""
                select 'unit owned by a non-active booking: unit ' || u.id
                from capacity_units u
                left join bookings b on b.id = u.booking_id and b.tenant_id = u.tenant_id
                where u.status in ('HELD', 'BOOKED')
                  and (b.id is null or b.status not in ('HELD', 'CONFIRMED'))
                """, String.class));
        violations.addAll(jdbc.queryForList("""
                select 'active booking without its claims: booking ' || b.id
                from bookings b
                where b.status in ('HELD', 'CONFIRMED')
                  and ((b.offering_kind = 'CAPACITY'
                        and not exists (select 1 from capacity_units u where u.booking_id = b.id and u.tenant_id = b.tenant_id))
                    or (b.offering_kind = 'INTERVAL'
                        and not exists (select 1 from interval_claims c where c.booking_id = b.id and c.tenant_id = b.tenant_id)))
                """, String.class));
        violations.addAll(jdbc.queryForList("""
                select 'overlapping interval claims: ' || a.id || ' and ' || b.id
                from interval_claims a
                join interval_claims b
                  on b.tenant_id = a.tenant_id and b.resource_id = a.resource_id and b.id > a.id
                where a.during && b.during
                """, String.class));
        violations.addAll(jdbc.queryForList("""
                select 'held too long past expiry: booking ' || id
                from bookings
                where status = 'HELD'
                  and hold_expires_at < now() - make_interval(secs => ?)
                """, String.class, graceSeconds + jobIntervalSeconds));
        return violations;
    }
}
