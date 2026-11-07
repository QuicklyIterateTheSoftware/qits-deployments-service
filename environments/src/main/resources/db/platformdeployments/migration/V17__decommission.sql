-- EVERY DECOMMISSION RUN, AND WHAT ITS PURGE OF THE APPLICATION'S ARTIFACTS CAME TO (qits-1941).
--
-- `POST /applications/{applicationId}/decommission` used to write a word on `pd_deployment` and
-- nothing else, so a retired application's images, maven and npm packages, docs and SBOMs stayed in
-- qits-artifacts for ever. It now asks qits-artifacts to purge them once the relabel has committed,
-- and this table is the account of each such run: who asked, which repositories it named, and either
-- the list qits-artifacts reported removed or why the purge failed. The deployment rows cannot carry
-- it — a second run would have to overwrite the first, and a purge is not a deployment's fact.
--
-- ONE ROW PER RUN, NEVER UPDATED. A failed purge is retried by calling the door again, which writes
-- a second row rather than correcting the first, so the history says the purge failed and then
-- succeeded. That is also why there is no unique key on the application.
--
-- `environment_id` is NULLABLE, like `pd_deployment`'s (V1), and copied from the row the run
-- settled; no FK into the topology, V1's rule: history outlives the tier.
--
-- `repositories` and `removed` are JSONB: lists, and `removed` is qits-artifacts' answer verbatim
-- (`[{"type","repository","name","versions"}]`). `removed` is null exactly when the purge FAILED —
-- an empty list is a purge that found nothing left, which is a different answer.
--
-- `purge_outcome` is varchar with no check constraint, V1's parity rule: the enum is the code's.
create table pd_decommission (
    id                uuid         not null primary key,
    application_name  varchar(64)  not null,
    environment_id    varchar(255),
    actor             text,
    decommissioned_at timestamp(6) with time zone not null,
    repositories      jsonb        not null,
    purge_outcome     varchar(16)  not null,
    removed           jsonb,
    failure_detail    text,
    causation_id      uuid
);
create index idx_pd_decommission_application on pd_decommission (application_name);
