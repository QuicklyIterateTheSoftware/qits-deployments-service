-- A RELEASE THAT WAITS FOR A PERSON (qits-2268).
--
-- qits-projects lets a release request be flagged "hold deploy", and qits-ci forwards it on
-- `SoftwareRelease` as `deployHold` beside `releaseRequestQualifiedId` (e.g. `qits-ci-service-rr-12`).
-- When such a release reaches this component, the quality gate HOLDS its deployment request: UNMET
-- with `gate_settled_at` left null — the gate has not answered, a person will — and no deployment
-- row, until `POST /deployment-requests/{id}/approve` answers it. The approval is recorded on the
-- request it answers:
--
--   deploy_hold                   the release asked to be held; false is every release that did not
--   release_request_qualified_id  the release request it came from, as qits-ci forwarded it
--   approved_by, approved_at      who approved a held request, and when — null while nobody has
--
-- `deploy_hold` is NOT NULL DEFAULT FALSE, and the default is the honest backfill rather than an
-- invention: no release before this column could ask to be held, and on the wire an absent key is
-- "not held" forever (CanonicalJson is NON_NULL). The other three are nullable with no backfill —
-- nothing before this column named a release request or approved anything.
alter table pd_deployment_request add column deploy_hold boolean not null default false;
alter table pd_deployment_request add column release_request_qualified_id text;
alter table pd_deployment_request add column approved_by text;
alter table pd_deployment_request add column approved_at timestamp(6) with time zone;

-- THE ACCEPTANCE LEDGER CARRIES THE HOLD TOO, for V11's reason: `OwedReleaseSweep` rebuilds the
-- whole announcement from this row, so a hold the ledger did not store would be a held release a
-- re-drive deploys unapproved.
alter table pd_owed_release add column deploy_hold boolean not null default false;
alter table pd_owed_release add column release_request_qualified_id text;
