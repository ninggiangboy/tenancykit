-- One row per (template, locale), holding all five parts, because the API updates a whole variant at once and a
-- per-part table would make "which parts does this override define" a query problem.
--
-- A template is the suite's content, not a tenant's: every tenant sees the same email, and only a Platform user at
-- platform scope may edit it. There is therefore no `tenant_id` and no row-level security here, and the key is
-- `(template_key, locale)`. A tenant administrator has no surface to rewrite the wording of an email or to inject
-- content into one.
--
-- A null part means "not overridden": the renderer fills it from the classpath default. `version` is the override's own
-- revision, not the default's, and the dispatch ledger records it so "which content produced this email" survives a
-- later edit. The classpath default is version 0, so an empty version in the ledger means the shared content shipped in
-- the jar.
create table notification_templates (
    template_key text        not null check (length(template_key) between 1 and 128),
    locale       text        not null check (locale in ('en', 'vi')),
    subject      text,
    html         text,
    text         text,
    title        text,
    body         text,
    version      integer     not null default 1 check (version >= 1),
    updated_at   timestamptz not null default now(),
    primary key (template_key, locale)
);
