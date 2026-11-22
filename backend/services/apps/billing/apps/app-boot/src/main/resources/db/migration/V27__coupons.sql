-- Coupons, promotion codes and the redemptions that run them.
--
-- A coupon is immutable terms plus a deactivation flag: it names one benefit, one duration, one scope and the bounds
-- on the coupon as a whole. A redemption is one coupon running on one wallet, and its counters only ever grow, so a
-- Charge recorded under it stays explainable. Money columns are minor units in the coupon's own currency.

create table coupons (
    id                             uuid primary key,
    tenant_id                      uuid        not null references tenants (id),
    name                           text        not null check (length(name) between 1 and 200),
    currency                       char(3)     not null,
    benefit_type                   text        not null check (benefit_type in ('PERCENT_OFF', 'AMOUNT_OFF', 'WALLET_CREDIT')),
    percent_basis_points           integer     check (percent_basis_points between 1 and 10000),
    amount_off                     bigint      check (amount_off > 0),
    credit_amount                  bigint      check (credit_amount > 0),
    window_count                   integer     check (window_count >= 1),
    window_unit                    text        check (window_unit in ('DAYS', 'MONTHS')),
    max_applications               integer     check (max_applications >= 1),
    max_total_discount             bigint      check (max_total_discount > 0),
    scope_kinds                    text[]      not null default '{}',
    scope_plan_ids                 uuid[]      not null default '{}',
    scope_metrics                  text[]      not null default '{}',
    redeem_by                      timestamptz,
    max_redemptions                bigint      check (max_redemptions >= 1),
    max_per_customer               integer     check (max_per_customer >= 1),
    min_charge                     bigint      check (min_charge > 0),
    max_discount_per_application   bigint      check (max_discount_per_application > 0),
    budget                         bigint      check (budget > 0),
    budget_used                    bigint      not null default 0 check (budget_used >= 0),
    redemptions_count              bigint      not null default 0 check (redemptions_count >= 0),
    stackable                      boolean     not null,
    status                         text        not null check (status in ('ACTIVE', 'INACTIVE')),
    created_by                     text        not null,
    created_at                     timestamptz not null,
    deactivated_at                 timestamptz,
    unique (tenant_id, id),
    unique (tenant_id, id, currency),
    -- A coupon gives away value, so it must carry a total bound: an expiry, a redemption count or a budget.
    check (redeem_by is not null or max_redemptions is not null or budget is not null),
    -- Exactly the benefit columns its type names are filled in.
    check (
        (benefit_type = 'PERCENT_OFF' and percent_basis_points is not null and amount_off is null and credit_amount is null)
        or (benefit_type = 'AMOUNT_OFF' and amount_off is not null and percent_basis_points is null and credit_amount is null)
        or (benefit_type = 'WALLET_CREDIT' and credit_amount is not null and percent_basis_points is null and amount_off is null)),
    check ((window_count is null) = (window_unit is null)),
    -- A budget is spent, never overspent.
    check (budget is null or budget_used <= budget),
    check ((status = 'INACTIVE') = (deactivated_at is not null))
);

create index coupons_status_idx on coupons (status, redeem_by);
call enable_tenant_rls('coupons');

create table promotion_codes (
    id              uuid primary key,
    tenant_id       uuid        not null references tenants (id),
    coupon_id       uuid        not null,
    code            text        not null check (code = upper(code) and length(code) between 1 and 64),
    max_redemptions bigint      check (max_redemptions >= 1),
    redeem_by       timestamptz,
    redeemed_count  bigint      not null default 0 check (redeemed_count >= 0),
    active          boolean     not null default true,
    created_by      text        not null,
    created_at      timestamptz not null,
    unique (tenant_id, id),
    unique (tenant_id, code),
    foreign key (tenant_id, coupon_id) references coupons (tenant_id, id),
    check (max_redemptions is null or redeemed_count <= max_redemptions)
);

create index promotion_codes_coupon_idx on promotion_codes (coupon_id);
call enable_tenant_rls('promotion_codes');

create table coupon_redemptions (
    id                 uuid primary key,
    tenant_id          uuid        not null references tenants (id),
    coupon_id          uuid        not null,
    code_id            uuid,
    wallet_id          uuid        not null,
    customer_id        uuid        not null,
    currency           char(3)     not null,
    status             text        not null check (status in ('ACTIVE', 'ENDED', 'REVOKED')),
    max_applications   integer     check (max_applications >= 1),
    max_total_discount bigint      check (max_total_discount > 0),
    applications_used  integer     not null default 0 check (applications_used >= 0),
    discount_total     bigint      not null default 0 check (discount_total >= 0),
    redeemed_at        timestamptz not null,
    ends_at            timestamptz,
    end_reason         text        check (end_reason in ('EXHAUSTED', 'REVOKED', 'COUPON_DEACTIVATED', 'GRANTED')),
    ended_at           timestamptz,
    actor              text        not null,
    idempotency_key    text        not null,
    request_hash       text        not null,
    unique (tenant_id, id),
    unique (tenant_id, idempotency_key),
    foreign key (tenant_id, coupon_id) references coupons (tenant_id, id),
    foreign key (tenant_id, code_id) references promotion_codes (tenant_id, id),
    foreign key (tenant_id, wallet_id, currency) references wallets (tenant_id, id, currency),
    foreign key (tenant_id, customer_id) references customers (tenant_id, id),
    check (max_applications is null or applications_used <= max_applications),
    check (max_total_discount is null or discount_total <= max_total_discount),
    check (ends_at is null or ends_at > redeemed_at),
    check ((status = 'ACTIVE') = (ended_at is null)),
    check (status = 'ACTIVE' or end_reason is not null)
);

-- One coupon runs on one wallet at a time, so a second attach of the same coupon is caught by the database.
create unique index coupon_redemptions_active_idx on coupon_redemptions (tenant_id, wallet_id, coupon_id) where status = 'ACTIVE';
create index coupon_redemptions_wallet_idx on coupon_redemptions (tenant_id, wallet_id, id);
create index coupon_redemptions_customer_idx on coupon_redemptions (tenant_id, customer_id, coupon_id);
call enable_tenant_rls('coupon_redemptions');
