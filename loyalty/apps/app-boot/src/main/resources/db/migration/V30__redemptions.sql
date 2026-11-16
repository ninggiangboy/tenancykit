-- Redemption: a POS spends a member's points at once and may reverse the spend when the sale is aborted.
--
-- Spending is not hold-and-commit. The member row is locked first, the lots are consumed by earliest expiry and the
-- redemption is stored in the same transaction, so two spends of one member run one after the other and the second sees
-- what the first left. The reversal restores exactly the lots the redemption consumed, which is why the ledger entry of
-- a redemption carries its id and `lot_allocations` records what it took from each lot.
create table redemptions (
    tenant_id       uuid        not null,
    id              uuid        not null,
    member_id       uuid        not null,
    -- The caller's own key for this spend, unique per tenant: the same key with the same body replays the stored
    -- redemption, and the same key with a different body is refused rather than applied twice.
    idempotency_key text        not null check (length(idempotency_key) between 1 and 200),
    -- The digest of the request body, so a reused key carrying another request is caught.
    fingerprint     text        not null,
    points          bigint      not null check (points > 0),
    -- The Tenant's own reference for the redemption, for example a POS receipt.
    reference       text        check (length(reference) <= 200),
    status          text        not null check (status in ('ACTIVE', 'REVERSED')),
    created_at      timestamptz not null,
    reversed_at     timestamptz,
    primary key (tenant_id, id),
    unique (tenant_id, idempotency_key),
    foreign key (tenant_id, member_id) references members (tenant_id, id),
    -- A reversed redemption says when; an active one has no reversal instant.
    check ((status = 'REVERSED') = (reversed_at is not null))
);

-- A member's redemptions newest first, which is how a statement or a review walks them.
create index redemptions_member_idx on redemptions (tenant_id, member_id, created_at desc, id desc);

call enable_tenant_rls('redemptions');
