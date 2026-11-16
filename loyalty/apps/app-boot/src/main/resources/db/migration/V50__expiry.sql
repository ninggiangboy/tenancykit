-- Point expiry and its advance notice.
--
-- A lot that expires simply stops counting: a balance is summed from the lots that expire in the future, so the run
-- below never changes what the API answers, it only makes the ledger say what the API already says. `expiry_noticed_at`
-- is the single row the notice run writes in addition to the event it queues, and it is what makes a lot announced at
-- most once however often the run repeats.

-- When the advance notice announced the lot; null until then.
alter table point_lots add column expiry_noticed_at timestamptz;

-- The expiry run looks for due lots across a whole tenant without naming a member first, which the member-first index of
-- the table cannot serve. The partial predicate keeps the index to the lots that can still be written off.
create index point_lots_expiry_idx on point_lots (tenant_id, expires_at) where remaining > 0;

-- The notice run is the same read narrowed to the lots nobody has announced yet.
create index point_lots_notice_idx on point_lots (tenant_id, expires_at)
    where remaining > 0 and expiry_noticed_at is null;
