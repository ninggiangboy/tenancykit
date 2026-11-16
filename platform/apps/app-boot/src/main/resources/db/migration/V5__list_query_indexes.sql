-- The list-query contract lets a caller sort on any field a listing marks sortable and search a text field with
-- contains, so each such field needs an index that serves it: a sortable column leads a full btree index (the primary
-- key already serves id, and the unique keys already serve tenants.code and api_credentials.key_id), and a contains
-- column carries a full GIN trigram index. The composite and functional indexes the earlier migrations create do not
-- count for a sort, because a sort on the column alone cannot use an index that does not start with it.
create extension if not exists pg_trgm;

create index tenants_name_listing_idx on tenants (name, id);
create index tenants_created_listing_idx on tenants (created_at, id);
create index tenants_name_trigram_idx on tenants using gin (name gin_trgm_ops);

create index users_email_listing_idx on users (email, id);
create index users_created_listing_idx on users (created_at, id);
create index users_email_trigram_idx on users using gin (email gin_trgm_ops);

create index roles_name_listing_idx on roles (name, id);
create index roles_created_listing_idx on roles (created_at, id);
create index roles_name_trigram_idx on roles using gin (name gin_trgm_ops);

create index tenant_systems_name_listing_idx on tenant_systems (name, id);
create index tenant_systems_created_listing_idx on tenant_systems (created_at, id);
create index tenant_systems_name_trigram_idx on tenant_systems using gin (name gin_trgm_ops);

create index api_credentials_created_listing_idx on api_credentials (created_at, id);
create index api_credentials_label_trigram_idx on api_credentials using gin (label gin_trgm_ops);
