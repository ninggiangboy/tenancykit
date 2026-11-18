-- The points context: the Tenant's members, the orders and returns it records so points are granted and taken back, the
-- append-only ledger, the lots a balance is summed from and the earn record whose snapshot makes a return reproducible.
--
-- A balance is never stored. It is the sum of `remaining` over unexpired lots minus `debt`, so a lot that expires is
-- simply no longer counted and no job has to rewrite a member's balance.

-- A member of the Tenant's program, keyed by the Tenant's own reference. `id` is a UUID v7 loyalty generates, so a
-- receiver can match the member across every event without the released reference.
--
-- An erased member keeps its row and its ledger, but its reference is nulled: `(tenant_id, external_ref)` is unique
-- and PostgreSQL treats nulls as distinct, so the same reference later enrols a new member.
create table members (
    tenant_id      uuid        not null,
    id             uuid        not null,
    external_ref   text        check (length(external_ref) between 1 and 128),
    display_name   text        check (length(display_name) <= 255),
    -- The tier code the member holds. A code, not an id: the program's read port hands tiers out by their code.
    tier_code      text        check (length(tier_code) between 1 and 100),
    -- Points owed from a clawback whose shortfall could not be taken from existing lots; the next earn repays it first.
    debt           bigint      not null default 0 check (debt >= 0),
    status         text        not null default 'ACTIVE' check (status in ('ACTIVE', 'ERASED')),
    -- When the tier evaluation should look at the member again.
    tier_review_at timestamptz,
    version        bigint      not null default 1 check (version >= 1),
    created_at     timestamptz not null,
    primary key (tenant_id, id),
    unique (tenant_id, external_ref),
    -- An erased member is exactly the one whose reference was released.
    check ((status = 'ERASED') = (external_ref is null))
);

call enable_tenant_rls('members');

-- One recorded order. An order is immutable once recorded: a mistake is corrected by a return, and a repeated call
-- with the same reference and body replays the stored answer.
create table orders (
    tenant_id   uuid        not null,
    id          uuid        not null,
    order_ref   text        not null check (length(order_ref) between 1 and 128),
    member_id   uuid        not null,
    currency    text        not null check (currency ~ '^[A-Z]{3}$'),
    occurred_at timestamptz not null,
    store_code  text        check (length(store_code) <= 100),
    channel     text        check (length(channel) <= 50),
    -- The digest of the request body, so the same reference with a different body is refused rather than applied.
    fingerprint text        not null,
    created_at  timestamptz not null,
    primary key (tenant_id, id),
    unique (tenant_id, order_ref),
    foreign key (tenant_id, member_id) references members (tenant_id, id)
);

-- The member's orders, newest first, which is the order a statement or a read walks.
create index orders_member_idx on orders (tenant_id, member_id, occurred_at desc);

call enable_tenant_rls('orders');

-- The line fields earning and returning need: no line name, no tax, no price breakdown. `quantity` allows three
-- fraction digits (a weighed item) and `amount` is minor units net of discount.
create table order_lines (
    tenant_id         uuid          not null,
    order_id          uuid          not null,
    line_no           integer       not null check (line_no >= 1),
    sku               text          check (length(sku) <= 100),
    category          text          check (length(category) <= 100),
    quantity          numeric(12, 3) not null check (quantity > 0),
    amount            bigint        not null check (amount >= 0),
    earn_eligible     boolean       not null default true,
    returned_quantity numeric(12, 3) not null default 0 check (returned_quantity >= 0),
    returned_amount   bigint        not null default 0 check (returned_amount >= 0),
    primary key (order_id, line_no),
    foreign key (tenant_id, order_id) references orders (tenant_id, id)
);

call enable_tenant_rls('order_lines');

-- One recorded return. Idempotent by `return_ref` alone: the reference names the return, and its fingerprint catches
-- the same reference carrying a different body.
--
-- `points_clawed_back` and `debt_added` are the points this return took back and the part of them that became debt, so
-- the order read can show what each return took without replaying the whole history of the order.
create table order_returns (
    tenant_id           uuid        not null,
    id                  uuid        not null,
    order_id            uuid        not null,
    return_ref          text        not null check (length(return_ref) between 1 and 128),
    occurred_at         timestamptz not null,
    fingerprint         text        not null,
    points_clawed_back  bigint      not null default 0 check (points_clawed_back >= 0),
    debt_added          bigint      not null default 0 check (debt_added >= 0),
    created_at          timestamptz not null,
    primary key (tenant_id, id),
    unique (tenant_id, return_ref),
    foreign key (tenant_id, order_id) references orders (tenant_id, id)
);

create index order_returns_order_idx on order_returns (tenant_id, order_id, created_at);

call enable_tenant_rls('order_returns');

create table order_return_lines (
    tenant_id uuid          not null,
    return_id uuid          not null,
    line_no   integer       not null check (line_no >= 1),
    quantity  numeric(12, 3) not null check (quantity > 0),
    amount    bigint        not null check (amount >= 0),
    primary key (return_id, line_no),
    foreign key (tenant_id, return_id) references order_returns (tenant_id, id)
);

call enable_tenant_rls('order_return_lines');

-- The append-only ledger. A correction is a new entry, never an edit: EARN grants, CLAWBACK takes points back on a
-- return, REDEEM spends, REDEEM_REVERSAL restores a reversed spend, EXPIRE writes a lot off and ERASE releases a
-- member's points. `points` is the magnitude the entry moved and `kind` gives the direction.
create table ledger_entries (
    id            uuid        not null,
    tenant_id     uuid        not null,
    member_id     uuid        not null,
    kind          text        not null check (kind in
                              ('EARN', 'CLAWBACK', 'REDEEM', 'REDEEM_REVERSAL', 'EXPIRE', 'ERASE')),
    points        bigint      not null check (points >= 0),
    order_id      uuid,
    return_id     uuid,
    redemption_id uuid,
    created_at    timestamptz not null,
    primary key (tenant_id, id),
    foreign key (tenant_id, member_id) references members (tenant_id, id)
);

-- The ledger read: one member's entries newest first, which is also the keyset the pagination walks.
create index ledger_entries_member_idx on ledger_entries (tenant_id, member_id, created_at desc, id desc);
create index ledger_entries_order_idx on ledger_entries (tenant_id, order_id) where order_id is not null;

call enable_tenant_rls('ledger_entries');

-- The lots a balance is summed from. `earned` is what the EARN entry granted the lot, `remaining` what is left of it;
-- a lot never expires into another row, it simply stops being counted once `expires_at` has passed.
create table point_lots (
    tenant_id  uuid        not null,
    id         uuid        not null,
    member_id  uuid        not null,
    entry_id   uuid        not null,
    order_id   uuid,
    earned     bigint      not null check (earned > 0),
    remaining  bigint      not null check (remaining >= 0),
    earned_at  timestamptz not null,
    expires_at timestamptz not null,
    primary key (tenant_id, id),
    foreign key (tenant_id, member_id) references members (tenant_id, id),
    check (remaining <= earned)
);

-- What a clawback or a redemption looks at: the live lots, earliest expiry first. The partial index is the whole
-- point of the table, so it is the one index the plan names.
create index point_lots_live_idx on point_lots (member_id, expires_at, id) where remaining > 0;

call enable_tenant_rls('point_lots');

-- Which lots one entry consumed and by how much, so a clawback can be explained lot by lot and a lot's history is
-- reconstructible from the ledger alone.
create table lot_allocations (
    tenant_id uuid   not null,
    entry_id  uuid   not null,
    lot_id    uuid   not null,
    points    bigint not null check (points > 0),
    primary key (entry_id, lot_id),
    foreign key (tenant_id, entry_id) references ledger_entries (tenant_id, id),
    foreign key (tenant_id, lot_id) references point_lots (tenant_id, id)
);

call enable_tenant_rls('lot_allocations');

-- What one order is worth and the rules that decided it. The snapshot is the single input of a recompute, so a return
-- never reads the rules of the day: a promotion archived after the order, a changed currency unit or a moved tier
-- cannot change what the order earned.
create table order_earns (
    tenant_id      uuid        not null,
    order_id       uuid        not null,
    member_id      uuid        not null,
    snapshot       jsonb       not null,
    granted_points bigint      not null default 0 check (granted_points >= 0),
    spend_amount   bigint      not null default 0 check (spend_amount >= 0),
    occurred_at    timestamptz not null,
    primary key (tenant_id, order_id),
    foreign key (tenant_id, order_id) references orders (tenant_id, id),
    foreign key (tenant_id, member_id) references members (tenant_id, id)
);

call enable_tenant_rls('order_earns');
