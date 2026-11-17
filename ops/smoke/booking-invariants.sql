-- Booking's invariants, run as the owner so every tenant is covered. Every query must return no rows.
\set ON_ERROR_STOP on

select 'capacity oversold: session ' || s.tenant_id || '/' || s.id
from sessions s
join capacity_units u on u.session_id = s.id and u.tenant_id = s.tenant_id
where u.status in ('HELD', 'BOOKED')
group by s.tenant_id, s.id, s.capacity
having count(*) > s.capacity;

select 'unit owned by a non-active booking: unit ' || u.tenant_id || '/' || u.id
from capacity_units u
left join bookings b on b.id = u.booking_id and b.tenant_id = u.tenant_id
where u.status in ('HELD', 'BOOKED')
  and (b.id is null or b.status not in ('HELD', 'CONFIRMED'));

select 'active booking without its claims: booking ' || b.tenant_id || '/' || b.id
from bookings b
where b.status in ('HELD', 'CONFIRMED')
  and ((b.offering_kind = 'CAPACITY'
        and not exists (select 1 from capacity_units u where u.booking_id = b.id and u.tenant_id = b.tenant_id))
    or (b.offering_kind = 'INTERVAL'
        and not exists (select 1 from interval_claims c where c.booking_id = b.id and c.tenant_id = b.tenant_id)));

select 'overlapping interval claims: ' || a.tenant_id || '/' || a.id || ' and ' || b.id
from interval_claims a
join interval_claims b
  on b.tenant_id = a.tenant_id and b.resource_id = a.resource_id and b.id > a.id
where a.during && b.during;

select 'held too long past expiry: booking ' || tenant_id || '/' || id
from bookings
where status = 'HELD'
  and hold_expires_at < now() - interval '5 minutes';
