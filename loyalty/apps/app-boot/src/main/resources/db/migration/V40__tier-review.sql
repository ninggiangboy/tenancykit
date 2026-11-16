-- The tier evaluation walks two things a day: the members whose review is due, in id order, and the orders that decide
-- one member's qualifying spend.

-- The due sweep selects the members of one tenant whose review instant has arrived; the partial index holds only the
-- members that have one, so a member with nothing pending costs the sweep nothing.
create index members_tier_review_idx on members (tenant_id, tier_review_at) where tier_review_at is not null;

-- A member's spend over the window is a sum over its earn records, so the read leads with the member and the instant the
-- window cuts on.
create index order_earns_member_occurred_idx on order_earns (tenant_id, member_id, occurred_at);
