-- Customers and webhook endpoints gain the version an ETag carries and an update compares under its row lock.
--
-- A constant default is catalog-only in PostgreSQL 11+, so each statement rewrites no table; the migrate profile's
-- 3s lock_timeout and retry already bound the brief ACCESS EXCLUSIVE lock the change still needs.

alter table customers add column version bigint not null default 1;
alter table webhook_endpoints add column version bigint not null default 1;
