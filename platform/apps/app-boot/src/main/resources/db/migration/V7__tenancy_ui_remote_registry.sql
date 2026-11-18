-- The console's UI remotes and the immutable versions each one has published.
--
-- Neither table carries a tenant id: the registry describes what the Console can load for every tenant, exactly as
-- `tenants` does, so both are plain tables that need no tenant context. A version is written once by CI and never
-- updated, so the integrity hash the shell verifies can never be swapped under it, and only an operator moves
-- `active_version`. A remote registered by CI starts disabled with no active version.

create table ui_remotes (
    name           text        primary key check (name ~ '^[a-z][a-z0-9-]{1,30}$'),
    app_code       text        not null,
    title          text        not null check (length(title) between 1 and 100),
    nav_order      integer     not null default 100 check (nav_order >= 0),
    audiences      text[]      not null check (cardinality(audiences) >= 1 and audiences <@ array['PLATFORM', 'TENANT']),
    enabled        boolean     not null default false,
    active_version text,
    created_at     timestamptz not null,
    version        bigint      not null default 1
);

create table ui_remote_versions (
    remote_name   text        not null references ui_remotes (name),
    version       text        not null check (version ~ '^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$'),
    entry_path    text        not null check (entry_path like '/console/remotes/%' and entry_path !~ '[?#]'),
    integrity     text        not null check (integrity ~ '^sha384-[A-Za-z0-9+/]{64}$'),
    sdk_major     integer     not null check (sdk_major >= 1),
    registered_at timestamptz not null,
    registered_by text        not null,
    primary key (remote_name, version)
);

-- The active version must be a registered version of the same remote. The column is nullable: a remote registered by
-- CI has none until an operator activates one.
alter table ui_remotes
    add constraint ui_remotes_active_version_fk
        foreign key (name, active_version) references ui_remote_versions (remote_name, version);

-- The listing sorts by nav order and by registration time, and searches the name with contains, so each needs the
-- index the list-query contract's coverage check expects. The primary key already serves a sort by name.
create index ui_remotes_nav_order_listing_idx on ui_remotes (nav_order, name);
create index ui_remotes_created_listing_idx on ui_remotes (created_at, name);
create index ui_remotes_name_trigram_idx on ui_remotes using gin (name gin_trgm_ops);
create index ui_remotes_app_code_idx on ui_remotes (app_code);
