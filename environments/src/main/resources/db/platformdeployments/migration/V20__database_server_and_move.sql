-- WHICH POSTGRES A CLAIM LIVES ON, AND EVERY MOVE OF ONE BETWEEN SERVERS AND NAMES (qits-2175).
--
-- The platform runs a second postgres now: `qits-oci-postgresql-private`, on qits-private only, beside
-- the shared `qits-oci-postgresql` on qits-net. A claim used to need no server — there was one, and
-- its address was derived from the tier alone — so `pd_resource` had no column to say which. It does
-- now, because the address a container is handed (`QITS_RESOURCE_<NAME>_URL`) and the statements
-- that provision the role and the database both depend on it.
--
-- `server` IS NOT NULL DEFAULT 'public', AND THE DEFAULT IS THE TRUTH RATHER THAN A GUESS: every row
-- that exists when this runs was provisioned on the one server there was. So the backfill is the
-- default itself and needs no statement of its own. A new claim takes the rule's server
-- (`DatabaseServer.forExposure`: an application declared `exposure: private` lands on the private
-- server, `public` and `both` on the public one), and an existing claim changes server only through a
-- move below.
--
-- THE CHECK CONSTRAINT IS A DELIBERATE EXCEPTION TO V1's PARITY RULE, and it is a narrow one. V1
-- keeps enum columns unchecked because the vocabulary grows in the code and a second list would have
-- to move in step. This one is not a vocabulary that grows by a word: it names an ADDRESS, the value
-- is read back to choose the host a credential is handed for, and a third value the code does not
-- know would be a container told to dial nothing. A row the code cannot route must not be writable.
alter table pd_resource add column server varchar(16) not null default 'public';
alter table pd_resource
    add constraint ck_pd_resource_server check (server in ('public', 'private'));

-- ONE ROW PER MOVE OF ONE CLAIM: a database copied from one (server, database) to another, verified
-- by exact per-table row counts, and the claim switched onto the copy.
--
-- It is a STATE MACHINE that has to survive this process, which is the whole reason it is a table:
-- a move stops the application that owns the data, closes the source database to connections, and
-- fills a target the application will be pointed at. A deployer that restarts in the middle of that
-- must find out what it was doing — and the answer is never "forget it", because forgetting it leaves
-- an application stopped and a database closed. `DatabaseMoves.settleInterrupted` reads the rows that
-- are not terminal at boot: before the switch it ABORTS (target dropped, source re-opened, the
-- service scaled back), from the switch on it FINISHES (the claim already points at the target).
--
-- `batch_id` groups the moves of one deployment: an application with two databases is stopped once,
-- copies both, and switches both or neither — a half-moved application would come back up against
-- one store it left behind.
--
-- `source_counts`/`target_counts` are the per-table manifests, JSONB objects of
-- `{"<schema>.<table>": <rows>}` written as JSON text (the V17 shape). Null until that side was read.
--
-- `kept_database` is what the source was renamed to at the switch, `<database>_moved_<yyyyMMddHHmm>`:
-- kept, closed to connections, until `drop_after`, then dropped by the retention sweep, which stamps
-- `dropped_at`. A move back to the original name later never collides with a kept copy.
--
-- `spec_server`/`spec_database` are what the release's own spec resolves to, recorded on an
-- operator's move so its answer can say where the next deployment will put the database back to.
--
-- No FK to pd_resource: a claim can be re-keyed by a rename transfer and the history must not follow
-- it or vanish with it. States are varchar with no check constraint, V1's parity rule — the enum is
-- the code's (`PdDatabaseMoveState`).
create table pd_database_move (
    id                varchar(36)  not null primary key,
    batch_id          varchar(36)  not null,
    application_name  varchar(64)  not null,
    environment_name  varchar(64)  not null,
    resource_name     varchar(64)  not null,
    source_server     varchar(16)  not null,
    source_database   varchar(64)  not null,
    target_server     varchar(16)  not null,
    target_database   varchar(64)  not null,
    spec_server       varchar(16),
    spec_database     varchar(64),
    kept_database     varchar(64),
    origin            varchar(16)  not null,
    requested_by      text,
    state             varchar(32)  not null,
    service_name      varchar(255),
    service_replicas  integer,
    source_counts     jsonb,
    target_counts     jsonb,
    detail            text,
    created_at        timestamp(6) with time zone not null,
    started_at        timestamp(6) with time zone,
    finished_at       timestamp(6) with time zone,
    drop_after        timestamp(6) with time zone,
    dropped_at        timestamp(6) with time zone
);
create index idx_pd_database_move_application on pd_database_move (application_name);
create index idx_pd_database_move_state on pd_database_move (state);
