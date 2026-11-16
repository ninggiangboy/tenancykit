-- A listing may be sorted by any field its schema marks sortable, so each such field leads an index. The creation
-- order and the id order coincide, so the primary keys already serve the default ordering.
create index payments_created_idx on payments (created_at, id);
create index payments_amount_idx on payments (amount, id);
create index invoices_created_idx on invoices (created_at, id);
create index invoices_total_idx on invoices (total, id);
