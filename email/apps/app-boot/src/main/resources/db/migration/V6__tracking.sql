-- Tracking links (context `sending`).
--
-- A click redirects to a URL recorded here at render time, looked up by (email, position) carried in a signed token.
-- The redirect never accepts a URL parameter, so the suite domain cannot become an open redirect. Links are removed
-- with the body; an old click then answers the static "link expired" page.
create table email_links (
    id         uuid        primary key,
    tenant_id  uuid        not null,
    email_id   uuid        not null references emails (id) on delete cascade,
    position   integer     not null check (position >= 0),
    url        text        not null check (length(url) between 1 and 4096 and url ~ '^https?://'),
    created_at timestamptz not null,
    unique (email_id, position)
);

create index email_links_email_idx on email_links (tenant_id, email_id);
call enable_tenant_rls('email_links');
