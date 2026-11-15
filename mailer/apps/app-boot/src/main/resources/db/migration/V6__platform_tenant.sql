-- The reserved Platform tenant does need mail.
--
-- Notification notifies a Platform administrator about their own access changes, exactly as it does a tenant user, and
-- those commands carry the reserved Platform tenant id. Mailer's jobs act per tenant through `TenantSweep`, which reads
-- this table; without a row here the command would be accepted into the ledger and then never claimed, so the email
-- would sit PENDING forever.
--
-- No credential row is required: an absent `mailer_credentials` row means "send through the suite's own identity",
-- which is exactly the SHARED behaviour Platform mail should have. Registering a SHARED row here would only be needed to
-- override the suite's region or from-address for Platform mail.
insert into mailer_tenants (tenant_id, active)
values ('00000000-0000-7000-8000-000000000001', true)
on conflict (tenant_id) do nothing;
