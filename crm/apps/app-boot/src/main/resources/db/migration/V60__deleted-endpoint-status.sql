-- A deleted webhook endpoint keeps its row so the deliveries it already received stay readable, so `DELETED` is a
-- status the table has to accept: the domain has always had it, and the check constraint the copy carried did not.
alter table webhook_endpoints
    drop constraint if exists webhook_endpoints_status_check;

alter table webhook_endpoints
    add constraint webhook_endpoints_status_check
        check (status in ('ACTIVE', 'DISABLED', 'DELETED'));
