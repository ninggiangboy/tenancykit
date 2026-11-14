-- Local rebuild state, not a replicated stream: one row says when a full rebuild last finished every stream.
--
-- `ReplicaBootstrap` reads it to decide whether the replica is complete. Without it the guard only counted tenant
-- rows, so a rebuild that stopped after the tenants stream looked finished, and the next boot skipped the rest:
-- tenant_domains (every host 404), user_grants (every request 401) and app_entitlements stayed empty until each row
-- happened to change. A rebuild that fails now leaves no marker and is retried in full on the next boot.
--
-- No row-level security: this is metadata about the replica as a whole, like the rebuild itself, which runs in the
-- platform scope. No version column either, because no event ever writes this row.
create table if not exists replica_state (
    id         smallint primary key default 1,
    rebuilt_at timestamptz not null,
    check (id = 1)
);
