-- A renewal's history records what the wallet paid after coupons, beside the period price it recorded before.
--
-- The column is nullable because rows written before coupons existed have no separate paid amount: their price and
-- what they paid are the same value, which a reader falls back to.

alter table subscription_events add column paid_amount bigint check (paid_amount >= 0);
