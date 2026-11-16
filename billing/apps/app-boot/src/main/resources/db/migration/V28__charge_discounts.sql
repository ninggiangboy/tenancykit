-- A charge records what it was worth before coupons, and the coupons it took off.
--
-- The charge amount stays "what the wallet paid" (the net), so the ledger, invoices, replay and the events keep their
-- meaning. The gross is amount + discount_total, and a zero amount is allowed only when a discount explains it.
--
-- The added check is not valid at first and validated after, so the table is not rewritten under a long lock; the
-- migrate profile's lock timeout and retry already bound the brief ACCESS EXCLUSIVE lock the column change needs.

alter table charges add column discount_total bigint not null default 0 check (discount_total >= 0);
alter table charges drop constraint charges_amount_check;
alter table charges add constraint charges_amount_check
    check (amount > 0 or (amount = 0 and discount_total > 0 and status = 'SUCCEEDED')) not valid;
alter table charges validate constraint charges_amount_check;

-- One coupon's contribution to one charge, in the order the engine applied them.
create table charge_discounts (
    charge_id     uuid    not null,
    tenant_id     uuid    not null references tenants (id),
    seq           integer not null check (seq >= 1),
    redemption_id uuid    not null,
    coupon_id     uuid    not null,
    coupon_name   text    not null check (length(coupon_name) between 1 and 200),
    benefit_type  text    not null check (benefit_type in ('PERCENT_OFF', 'AMOUNT_OFF', 'WALLET_CREDIT')),
    currency      char(3) not null,
    amount        bigint  not null check (amount > 0),
    primary key (charge_id, seq),
    foreign key (tenant_id, charge_id) references charges (tenant_id, id),
    foreign key (tenant_id, redemption_id) references coupon_redemptions (tenant_id, id)
);

call enable_tenant_rls('charge_discounts');
