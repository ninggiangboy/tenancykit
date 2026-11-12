-- The Platform service gets its own database on the development PostgreSQL. Each service owns its schema; no
-- service can reach another's database. The per-service layout (one container per service database) lands with the
-- suite runtime.
create database platform owner billing;
grant all privileges on database platform to billing;
