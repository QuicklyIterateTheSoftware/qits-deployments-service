-- THE DEPLOYER'S OWN SETTINGS STORE (qits-1390, epic qits-765 "Remove the qits-configuration
-- service").
--
-- One row is one environment variable for one application in one tier: a secret or an installation
-- fact that today lives in qits-configuration's extras and is moving here. THIS MIGRATION ONLY ADDS
-- THE STORE. No deployment reads it yet — `SwarmDeploymentDriver` still takes an application's env
-- from `DeploymentExtrasSource` alone — so a row written here changes nothing on a running service
-- until the read is switched over in a later step. The preview door
-- (`GET /deployments/api/settings/preview/{application}/{environment}`) is what shows what that
-- switch WOULD change, before it is made.
--
-- `setting_key` is the env var NAME itself (`[A-Z_][A-Z0-9_]*`, validated at the door), never the
-- dotted `qits.deployments.extras.<app>.env.<KEY>` spelling: the row is already addressed by its
-- application and tier, so the prefix would only be a second copy of the columns beside it.
--
-- `environment_name` is NOT NULL, unlike `pd_resource`'s: the platform plane that a null tier used to
-- mean is deleted (V13), so there is no row this table could hold that a tier does not name — and a
-- not-null column makes the plain unique constraint below the whole key, with no `nulls not distinct`
-- needed.
--
-- `value` is `text`, not `varchar(n)`: a value may be a PEM, a JSON document or a long url, and a
-- length chosen here would be a refusal nobody asked for. `secret` says only how the value is SHOWN —
-- never on any read door — and not how it is stored: the database is credential-bearing already
-- (`pd_resource.password`), and this is the same database.
--
-- NO FOREIGN KEY to `pd_environment` or `pd_service`: a tier and an application are named by plain
-- strings here as they are in `pd_resource` and `pd_deployment`, because a setting outlives the row
-- that described its tier (and is written BEFORE the application's first deployment, which is the
-- point of being able to state a secret ahead of time).
create table pd_setting (
    id               varchar(255) not null primary key,
    application_name varchar(64)  not null,
    environment_name varchar(64)  not null,
    setting_key      varchar(255) not null,
    value            text         not null,
    secret           boolean      not null,
    updated_at       timestamp(6) with time zone not null,
    updated_by       text,
    constraint uq_pd_setting unique (application_name, environment_name, setting_key)
);
