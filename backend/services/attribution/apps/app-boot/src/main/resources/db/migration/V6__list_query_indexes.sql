-- Every listing sorts only on the fields its schema marks sortable, and a sort must not scan the whole table, so each
-- sortable field leads a full btree index of its own table. `id` is the tie-breaker of every list here and is served by
-- the primary key, so it needs no index of its own. `contains` is case-insensitive substring search, which only a
-- trigram index can serve; `slug` is the one field that offers it.

create extension if not exists pg_trgm;

create index campaigns_name_listing_idx on campaigns (name, id);
create index campaigns_created_listing_idx on campaigns (created_at, id);

create index links_created_listing_idx on links (created_at, id);
create index links_slug_trigram_idx on links using gin (slug gin_trgm_ops);

create index goals_key_listing_idx on goals (key, id);
create index goals_created_listing_idx on goals (created_at, id);

create index conversions_value_listing_idx on conversions (value, id);
create index conversions_occurred_listing_idx on conversions (occurred_at, id);
