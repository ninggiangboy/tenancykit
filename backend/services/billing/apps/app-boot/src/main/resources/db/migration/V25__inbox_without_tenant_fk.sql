-- The inbox records that a delivery was seen, and a delivery about a tenant this app has not replicated yet is
-- exactly the case the inbox must survive: `tenant.changed` is what creates the tenant row. The foreign key to
-- `tenants` made that first event impossible to record, so a tenant created after Billing started never reached the
-- replica at all. The inbox is keyed by the event's tenant id, which is a value from Platform, not a reference.
--
-- `core-messaging`'s schema contract for `event_inbox` requires no foreign key; row-level security on `tenant_id`
-- remains the isolation guarantee, and it still applies after the constraint is gone.
alter table event_inbox drop constraint if exists event_inbox_tenant_id_fkey;
