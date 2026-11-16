-- Every listing may be sorted by each field its schema marks sortable, and a filter with contains needs a trigram
-- index. Each index here leads with its column; the primary key already serves id.

create extension if not exists pg_trgm;

create index customers_created_listing_idx on customers (created_at, id);
create index customers_name_listing_idx on customers (display_name, id);

create index wallets_created_listing_idx on wallets (created_at, id);

create index charges_created_listing_idx on charges (created_at, id);
create index charges_amount_listing_idx on charges (amount, id);
create index charges_reference_listing_idx on charges (reference);

create index reservations_created_listing_idx on reservations (created_at, id);
create index reservations_reference_listing_idx on reservations (reference);

create index ledger_entries_seq_listing_idx on ledger_entries (seq);
create index ledger_entries_created_listing_idx on ledger_entries (created_at, id);

create index subscriptions_created_listing_idx on subscriptions (created_at, id);

create index usage_events_occurred_listing_idx on usage_events (occurred_at, id);
create index usage_events_recorded_listing_idx on usage_events (recorded_at, id);
create index usage_events_metric_trigram_idx on usage_events using gin (metric gin_trgm_ops);

create index coupons_created_listing_idx on coupons (created_at, id);
create index coupons_name_listing_idx on coupons (name, id);

create index promotion_codes_created_listing_idx on promotion_codes (created_at, id);
create index promotion_codes_code_listing_idx on promotion_codes (code, id);

create index coupon_redemptions_redeemed_listing_idx on coupon_redemptions (redeemed_at, id);

create index webhook_deliveries_created_listing_idx on webhook_deliveries (created_at, id);
create index webhook_endpoints_created_listing_idx on webhook_endpoints (created_at, id);

create index stripe_events_received_listing_idx on stripe_events (received_at, id);

create index stripe_incidents_created_listing_idx on stripe_incidents (created_at, id);
create index stripe_incidents_last_seen_listing_idx on stripe_incidents (last_seen_at, id);

create index pricing_plans_created_listing_idx on pricing_plans (created_at, id);
create index pricing_plans_code_listing_idx on pricing_plans (code, id);
create index pricing_plans_name_listing_idx on pricing_plans (name, id);

create index refunds_created_listing_idx on refunds (created_at, id);
create index refunds_amount_listing_idx on refunds (amount, id);
