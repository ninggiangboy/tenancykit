-- Extra transport headers a producer asked for, kept beside the body so a claimed delivery carries everything one
-- send needs. The allowlist (today `List-Unsubscribe` and `List-Unsubscribe-Post`) is the command contract's, not
-- this schema's: storing the subset as jsonb means mailer describes no header name of its own and a future
-- allowlisted header needs no migration.
alter table email_bodies add column headers jsonb;
