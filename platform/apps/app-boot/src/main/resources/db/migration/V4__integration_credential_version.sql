-- A credential now carries an optimistic-lock version like every other aggregate row. Rotation and revocation
-- read their row under the Tenant System's lock and write it back with this guard, so a write that did not see a
-- committed revocation can never resurrect it and two rotations can never both extend the same credential.

alter table api_credentials
    add column version bigint not null default 1;
