-- The rewards catalog and the single-use vouchers a reward redemption issues.
--
-- A reward is a catalog entry a member exchanges points for: a cost in points, an optional stock (null is unlimited) and
-- an optional validity window. Redeeming it spends the points and issues one voucher in one transaction, so the reward's
-- stock and the member's points move together or not at all. A cost or stock change never reaches a voucher already
-- issued: the voucher keeps the expiry its own `voucher_valid_days` gave it at issue time.
create table rewards (
    tenant_id          uuid        not null,
    id                 uuid        not null,
    name               text        not null check (length(name) between 1 and 255),
    description        text        check (length(description) <= 1000),
    points_cost        bigint      not null check (points_cost >= 0),
    -- Remaining units. Null is unlimited, which is why the check below admits it.
    stock              bigint      check (stock >= 0),
    status             text        not null check (status in ('ACTIVE', 'ARCHIVED')),
    valid_from         timestamptz,
    valid_to           timestamptz,
    voucher_valid_days integer     not null check (voucher_valid_days >= 1),
    version            bigint      not null default 1 check (version >= 1),
    created_at         timestamptz not null,
    updated_at         timestamptz not null,
    primary key (tenant_id, id),
    -- A window that ends before it starts is not a window.
    check (valid_from is null or valid_to is null or valid_to >= valid_from)
);

-- The catalog read: one tenant's rewards by state, oldest first, which is also the keyset the listing walks.
create index rewards_catalog_idx on rewards (tenant_id, status, created_at, id);

call enable_tenant_rls('rewards');

-- One single-use voucher, issued by a reward redemption. `code` is what the Tenant's point of sale presents, so it is
-- unique per tenant and short enough to read out loud.
--
-- `external_ref` is the member's reference copied at issue time: a voucher read and the use event name the member by it,
-- and this context reads no member row. Erasing the member clears it here together with the ERASURE void.
create table vouchers (
    tenant_id     uuid        not null,
    id            uuid        not null,
    code          text        not null check (length(code) between 1 and 100),
    reward_id     uuid        not null,
    member_id     uuid        not null,
    redemption_id uuid        not null,
    external_ref  text        check (length(external_ref) between 1 and 200),
    status        text        not null check (status in ('ISSUED', 'USED', 'VOIDED')),
    issued_at     timestamptz not null,
    expires_at    timestamptz not null,
    used_at       timestamptz,
    use_ref       text        check (length(use_ref) between 1 and 200),
    void_reason   text        check (void_reason in ('REVERSAL', 'ERASURE')),
    primary key (tenant_id, id),
    unique (tenant_id, code),
    foreign key (tenant_id, member_id) references members (tenant_id, id),
    foreign key (tenant_id, reward_id) references rewards (tenant_id, id),
    foreign key (tenant_id, redemption_id) references redemptions (tenant_id, id),
    -- A used voucher says when; an issued one has no use instant.
    check ((status = 'USED') = (used_at is not null)),
    -- A voided voucher says why; a live or used one carries no reason.
    check ((status = 'VOIDED') = (void_reason is not null))
);

-- The member's vouchers, narrowed by state: what the member's voucher list walks.
create index vouchers_member_idx on vouchers (tenant_id, member_id, status, issued_at desc, id desc);

-- The reversal asks for the voucher by the redemption that issued it.
create index vouchers_redemption_idx on vouchers (tenant_id, redemption_id);

call enable_tenant_rls('vouchers');
