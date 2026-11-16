-- A HELD RELEASE A PERSON SAYS NO TO (qits-2271).
--
-- V22 let a release be held for a person's approval, and approve was the only answer: a held row
-- nobody approved stayed UNMET and unsettled for good. `POST /deployment-requests/{id}/decline` is
-- the other answer. It settles the gate UNMET (gate_settled_at set, nothing queued, nothing
-- deployed) and records who said no, and when:
--
--   declined_by, declined_at   who declined a held request, and when — null while nobody has
--
-- They are separate from approved_by/approved_at on purpose: `findApproved` reads approved_at as
-- "a person said yes", and a re-announced declined version must never pass as MET through it.
-- Both nullable with no backfill — nothing before this column declined anything.
alter table pd_deployment_request add column declined_by text;
alter table pd_deployment_request add column declined_at timestamp(6) with time zone;
