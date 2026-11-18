-- A sub-workflow starts a child instance and waits for it. The parent links make the chain visible and bound its depth,
-- and the child's finish is what resumes the token that started it.
alter table workflow_instance add column parent_instance_id uuid references workflow_instance (id);
alter table workflow_instance add column parent_token_id uuid;
alter table workflow_instance add column depth integer not null default 0 check (depth >= 0);

create index workflow_instance_parent_idx on workflow_instance (parent_instance_id)
    where parent_instance_id is not null;
