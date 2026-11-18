-- Every listing may be sorted by each field its schema marks sortable, and a filter with contains needs a trigram
-- index. Each index here leads with its column; the primary key already serves id.

create extension if not exists pg_trgm;

create index email_templates_created_listing_idx on email_templates (created_at, id);
create index email_templates_key_listing_idx on email_templates (key, id);
create index email_templates_name_listing_idx on email_templates (name, id);

create index email_template_revisions_listing_idx on email_template_revisions (revision, id);
create index email_template_revisions_created_idx on email_template_revisions (created_at, id);

create index campaigns_created_listing_idx on campaigns (created_at, id);
create index campaigns_name_listing_idx on campaigns (name, id);

create index emails_created_listing_idx on emails (created_at, id);
create index emails_to_address_trigram_idx on emails using gin (to_address gin_trgm_ops);

create index receivers_created_listing_idx on receivers (created_at, id);
create index receivers_email_listing_idx on receivers (email, id);
create index receivers_email_trigram_idx on receivers using gin (email gin_trgm_ops);

create index receiver_groups_created_listing_idx on receiver_groups (created_at, id);
create index receiver_groups_name_listing_idx on receiver_groups (name, id);

create index segments_created_listing_idx on segments (created_at, id);
create index segments_name_listing_idx on segments (name, id);

create index suppressions_address_listing_idx on suppressions (email_normalized);
create index suppressions_created_listing_idx on suppressions (created_at);
