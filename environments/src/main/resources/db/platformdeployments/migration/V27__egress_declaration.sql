-- THE ROUTES AN APPLICATION SENDS THROUGH qits-egress, AND THE TABLE THEY MAKE (epic qits-2523).
--
-- pd_egress_declaration: one row per (application, released version) — what the repository's
-- `.config/qits/egress.yml` said at that version's tag, read beside deployments.yml when the release
-- was read. Keyed by the VERSION rather than by a deployment row, because a tag is immutable and
-- every row that runs that version — a start, a rollback, a re-announcement — runs the same file.
-- The grant table joins it to each application's ACTIVE pd_deployment on (application_name, version).
--
--   routes   the parsed routes as JSON text, `[]` for a file that grants none or no file at all
--            (the git host's 404); NULL exactly when the file was refused
--   refused  why the file was refused — a parse error, or a read that never answered — NULL when it
--            parsed. A refusal never fails the deployment: that version simply carries no routes.
--   read_at  when it was read; a re-read of the same version (a re-announced release) replaces it
--
-- No row is a version read before this table existed, or a variant (its file is the main
-- application's): no routes either way. No backfill — nothing was read.
create table pd_egress_declaration (
    id                text primary key,
    application_name  varchar(64) not null,
    version           varchar(64) not null,
    routes            jsonb,
    refused           text,
    read_at           timestamp(6) with time zone not null,
    constraint uq_pd_egress_declaration unique (application_name, version)
);

-- pd_egress_grant_table: the one table in force, V23's pd_edge_route_table shape with one row
-- (`id` = 'egress'). `version` only ever goes up — a changed content_hash gives max(stored + 1,
-- epoch millis now) — so a consumer's last version is never answered with a lower one; `body` is
-- the table exactly as GET /deployments/api/egress-grants answers it.
create table pd_egress_grant_table (
    id            text primary key,
    version       bigint not null,
    content_hash  text not null,
    body          jsonb not null,
    built_at      timestamp(6) with time zone not null
);
