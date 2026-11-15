-- THE ROUTE TABLES THIS COMPONENT PUSHES TO THE EDGES (epic qits-2174, task qits-2198).
--
-- One row per edge: `public` (the application qits-edge) and `private` (qits-edge-private). The
-- table is built from the swarm services' own labels (qits.platform.deployments.exposure, routes,
-- hosts, upstream-port, api-docs, navigation) — never from pd_deployment — and pushed to the edge as
-- `body`, verbatim; `GET /deployments/api/edge-routes/{edge}` answers the same body.
--
--   version       only ever goes up: a changed content_hash gives max(stored + 1, epoch millis now),
--                 so a deployer that lost this table still never answers a lower number than an
--                 edge already holds (the edge ignores a version not above the one it holds)
--   content_hash  sha-256 of the canonical JSON of the table's applications + withdrawn, so an
--                 unchanged estate keeps its version however often it is rebuilt
--   body          the whole table as pushed, version and builtAt included
--   built_at      when this version was built
--
-- The table name is a stated value rather than an enum column for the same reason exposure is a
-- string on the wire: the two edges are the edge's vocabulary, and the deployer checks the name in
-- code (EdgeRouteTables.EDGES).
create table pd_edge_route_table (
    edge          text primary key,
    version       bigint not null,
    content_hash  text not null,
    body          jsonb not null,
    built_at      timestamp(6) with time zone not null
);
