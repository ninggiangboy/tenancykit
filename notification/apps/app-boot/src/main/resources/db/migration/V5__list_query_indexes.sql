-- The list-query contract lets a caller sort on any field the listing marks sortable and search a text field with
-- contains, so each such field needs an index that serves it: a sortable column leads a full btree index (the primary
-- key already serves id), and a contains column carries a full GIN trigram index. The partial and composite indexes
-- the earlier migrations create do not count, because a sort on the column alone cannot use an index that does not
-- start with it.
create extension if not exists pg_trgm;

create index notifications_created_listing_idx on notifications (created_at, id);

create index email_dispatches_created_listing_idx on email_dispatches (created_at, id);
create index email_dispatches_recipient_trigram_idx on email_dispatches using gin (recipient gin_trgm_ops);
