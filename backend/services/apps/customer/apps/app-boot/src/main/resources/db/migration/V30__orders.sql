-- The Tenant's orders with their lines and the returns recorded against them.
--
-- An order is immutable once recorded: a correction is a return, never an edit. That is why the order carries
-- `fingerprint` of the request that recorded it — the same `order_ref` with the same body replays the stored answer,
-- and the same `order_ref` with another body is a conflict. `version` is 1 when the order is recorded and rises by
-- each return; it is the order's ETag and the `orderVersion` every order webhook carries.
--
-- `total` is informational: tax, delivery and order-level discounts make it differ from the sum of the lines, and
-- nothing reads it as an earn base. Amounts are minor units of `currency`; quantities allow three fraction digits
-- because a scale or a weight sells a fraction of an item.
--
-- Returned quantity and amount are cumulative on the line, and the check constraints below are the last line of
-- defence behind the order row lock: two returns of one order run one after the other, and neither can take more
-- than was sold even if the application rule is bypassed.
--
-- Every table carries `tenant_id` and row-level security, including the child tables, so a query that forgets a join
-- still cannot read across tenants.

create table orders (
    tenant_id   uuid        not null,
    id          uuid        primary key,
    -- The Tenant's reference: unique per tenant, immutable, and the idempotency key of the order.
    order_ref   text        not null check (length(order_ref) between 1 and 200),
    customer_id uuid        not null references customers (id),
    currency    text        not null check (currency ~ '^[A-Z]{3}$'),
    total       bigint      not null check (total >= 0),
    occurred_at timestamptz not null,
    store_code  text        not null check (length(store_code) <= 100),
    channel     text        not null check (length(channel) <= 100),
    status      text        not null check (status in ('RECORDED', 'PARTIALLY_RETURNED', 'RETURNED')),
    version     bigint      not null default 1 check (version >= 1),
    -- The digest of the request that recorded the order, so a replay is told from a changed body.
    fingerprint text        not null,
    created_at  timestamptz not null,
    unique (tenant_id, order_ref)
);

-- A customer's order history is paged newest first and breaks ties on the id, so the cursor is a range scan on one
-- index. The unique key above serves the lookup by reference.
create index orders_customer_keyset_idx on orders (tenant_id, customer_id, occurred_at desc, id desc);

call enable_tenant_rls('orders');

create table order_lines (
    tenant_id         uuid           not null,
    order_id          uuid           not null references orders (id) on delete cascade,
    -- The line's number within its order, starting at 1, as the Tenant's system numbered it.
    line_no           integer        not null check (line_no >= 1),
    sku               text           not null check (length(sku) between 1 and 100),
    name              text           check (name is null or length(name) <= 500),
    category          text           not null check (length(category) between 1 and 100),
    quantity          numeric(12, 3) not null check (quantity > 0),
    -- The line's net amount, net of discount, never negative.
    amount            bigint         not null check (amount >= 0),
    earn_eligible     boolean        not null default true,
    returned_quantity numeric(12, 3) not null default 0 check (returned_quantity >= 0),
    returned_amount   bigint         not null default 0 check (returned_amount >= 0),
    primary key (order_id, line_no),
    check (returned_quantity <= quantity),
    check (returned_amount <= amount)
);

create index order_lines_order_idx on order_lines (tenant_id, order_id);

call enable_tenant_rls('order_lines');

create table order_returns (
    tenant_id   uuid        not null,
    id          uuid        primary key,
    order_id    uuid        not null references orders (id) on delete cascade,
    -- The Tenant's reference: unique per tenant, immutable, and the idempotency key of the return.
    return_ref  text        not null check (length(return_ref) between 1 and 200),
    occurred_at timestamptz not null,
    -- The order version this return produced, so the returns read back in the order they were recorded.
    version     bigint      not null check (version >= 2),
    fingerprint text        not null,
    created_at  timestamptz not null,
    unique (tenant_id, return_ref)
);

create index order_returns_order_idx on order_returns (tenant_id, order_id, version);

call enable_tenant_rls('order_returns');

create table order_return_lines (
    tenant_id uuid           not null,
    return_id uuid           not null references order_returns (id) on delete cascade,
    line_no   integer        not null check (line_no >= 1),
    quantity  numeric(12, 3) not null check (quantity > 0),
    amount    bigint         not null check (amount >= 0),
    primary key (return_id, line_no)
);

create index order_return_lines_return_idx on order_return_lines (tenant_id, return_id);

call enable_tenant_rls('order_return_lines');
