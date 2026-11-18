-- A listing may be sorted by any field its schema marks sortable, so each such field leads a full index of its table;
-- `id` is the tie-breaker every ordering ends with and is already the primary key of every table here. The indexes the
-- migrations already declared lead with `tenant_id`, which row-level security uses but which does not serve a sort.
--
-- A survey's name is also searched with `contains`, which needs a trigram index to be an index scan rather than a walk.

create extension if not exists pg_trgm;

create index surveys_name_idx on surveys (name, id);
create index surveys_created_idx on surveys (created_at, id);
create index surveys_updated_idx on surveys (updated_at, id);
create index surveys_name_trgm_idx on surveys using gin (name gin_trgm_ops);

create index survey_versions_version_no_idx on survey_versions (version_no, id);

create index survey_links_created_idx on survey_links (created_at, id);

create index responses_started_idx on responses (started_at, id);

create index visits_opened_idx on visits (opened_at, id);
create index visits_last_activity_idx on visits (last_activity_at, id);

create index exports_created_idx on exports (created_at, id);

create index webhook_deliveries_created_idx on webhook_deliveries (created_at, id);
create index webhook_endpoints_created_idx on webhook_endpoints (created_at, id);
