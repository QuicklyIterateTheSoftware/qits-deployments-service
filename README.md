# qits-deployments-platform-service

The platform component that owns **what runs where**. Both halves of it: the *topology* — which
environments exist, which services are linked into each of them, what shape each service has — and
the *execution* — pulling an image, starting a container, health-gating it, cutting over, and
recording what happened.

It is the merge-back of `qits-cd` and `qits-serviceregistry`, re-partitioned. Both are superseded.

## Why one component

The split looked clean and was not. The executor held the docker socket and, on every green build,
fanned out over **every** environment whose branch matched — cross-environment behaviour, wearing an
environment citizen's label. The registry that owned the topology could act on none of it and was
the passive half. So the two most closely coupled operations on the platform — *register this
service where this build belongs* and *deploy it there* — became an HTTP round trip between two
databases, with a client, a bearer to mint, a stub server in the test suite, and a recorded-FAILED
posture for the case where one half could not reach the other.

None of that was buying isolation. It was paying for a boundary drawn in the wrong place.

One component, one database, one docker socket. Registration writes rows in the same transaction as
everything else; resolution is a repository query with an index behind it. The modules below are a
partition of the **code**, which is where the partition was always useful.

## The three modules

    environments/   the topology domain — environments, services, links, and the rules over them.
                    Owns the datasource and the single Flyway lineage. Knows nothing about
                    containers.
    deployments/    the execution domain — deployment rows, the deploy orchestration, the rollback
                    pins, the resource registry, the strict spec parser, and the three SEAMS it
                    cannot implement itself.
    service/        the adapters — JAX-RS for both domains, the swarm driver, the git-host spec
                    reader, the postgres provisioner, the build-succeeded intake, and the web
                    client.

`deployments` depends on `environments` and never the reverse: execution reads and writes the
topology, the topology knows nothing about execution. Neither domain module carries JAX-RS, an HTTP
client or a process shell-out.

## The model: tiers and derived rows

An **environment is a tier** — today just dev. It is created deliberately, over REST, with the
bundle network's convention filled in (`qits-env-<name>`). It has **no branch**: a tier listened to
`environment/<name>` while a green build was the deploy trigger, and a release names a tag, so
where a version lands is a property of the platform rather than of the thing being deployed.

**One tier is designated the platform environment** (`pd_environment.platform`, true on exactly one
row), and it decides one thing: it is where a release ENTERS the platform.

A **service** has one row for the whole platform, not one per tier, and says where it runs by
carrying a **link** to each environment. There is one kind of service: one instance per linked tier,
on that tier's networks, deployed into the tier the release entered at.

**There used to be a second, and deleting it is the change this model is shaped by.** A `platform`
service was one instance for the whole platform, on every environment's networks, reached under its
BARE name (`qits-ci`, not `dev-qits-ci`) from every tier — and it carried **no links at all**, which
was the mechanism rather than an omission: "present everywhere" was spelled as "linked nowhere in
particular", so an environment created tomorrow picked up qits-platform-idp with nobody editing a
row. It is gone: `deployment_target` is a retired spec key that is read and dropped, the enum and both
columns are deleted, and a service linked nowhere runs nowhere. The nine services that were the plane
are ordinary rows in the designated tier, linked into it by the migration that deleted the plane.

The one thing that cost something is the **address**: a swarm service's name IS its wire alias and
swarm cannot rename one, so the nine were renamed once, by being granted the qualified name as an
extra alias one release before the derivation flipped. Their bare-named predecessors are retired by
hand — `AGENTS.md`, *Retiring the plane's bare-named services*.

**Nothing declares a service.** Rows are **derived**: a green build sends this component to the
repository's `.config/qits/deployments.yml` at that sha, and the service row is created or brought
up to date from what it found there.

```yaml
application: qits-ci                 # optional; what this deploys AS, when it is not the repo's name
deployment_target: environment       # RETIRED; still parsed for old tags, acted on by nobody
available_on_env: false              # default; true = public node (bundle + hub joins)
deploy_branches: environment/prod    # RETIRED; still parsed for old tags, acted on by nobody
health_path: /q/health/ready         # default: /<name without the qits- prefix>/q/health/ready
health_cmd: pg_isready -U postgres   # instead of health_path; runs inside the container
resources: postgresql:db             # a database of its own, injected as QITS_RESOURCE_DB_*
update_order: start-first            # default | stop-first for anything that cannot overlap itself
publish_mode: host                   # default | ingress for a port swarm's routing mesh holds
routes: /artifacts,/artifacts/api    # optional public path prefixes, ordered
upstream_port: 8080                  # default; target port behind every declared route
navigation: Artifacts:3              # optional label:positive-position for the first route
```

`application:` is what this repository **deploys as**, and it is the one key that changes an
identity rather than a behaviour. Absent — every file today — the application name is the
repository's name and nothing is different. Present, that string is the application name everywhere:
the swarm service and its wire alias, the container name, the image `qits/<application>:<sha>`, the
provisioned database, the derived host label, the catalogue key, the extras family and every
`Deployment*` event. It exists so a repository can be **renamed** without moving anything that runs —
`qits-ci` becomes the repository `qits-ci-service`, writes `application: qits-ci`, and the platform
does not notice. Two repositories claiming one application name is last-wins and this component
cannot refuse it (these tables record an application name and no repository identity); changing the
key on a live application is a decommission and a new application, not a rename.

`deploy_branches` is **retired**: parsed, validated and acted on by nobody. Where a release lands is
the environment rows' answer — the designated platform environment — and never the file's. The
tolerance stays because a spec is fetched at the RELEASED tag, so a redeploy of an older version
still presents a file carrying the key, and an unknown key fails a deployment.

`routes:` is the service-owned public surface: comma-separated, unique absolute lowercase path
prefixes. `upstream_port:` is shared by those prefixes and defaults to `8080`. `navigation:` is
optional, splits on its final colon into a visible label and a positive position, and belongs to
the first declared route. After cutover, `DeploymentActive` publishes the complete resolved list:
each route carries its path, the deployment's runtime wire alias, port, and (for the first route)
navigation metadata. Consumers replace their projection from that snapshot; an empty list removes
the application's previous routes and navigation entry. A declaration without `routes:` remains
valid and publishes an empty list, which is the compatibility posture for every existing service.

`health_cmd` and `health_path` are **alternatives, and a file setting both fails**. The path names a
URL a `curl` inside the container fetches; the command replaces that mechanism whole. It is for the
deployable images — a plain postgres, the first of them — which have neither curl nor anything on
8080 and so can pass no path-shaped gate. The value reaches `--health-cmd` verbatim, one argv
element, run by the container's own `/bin/sh -c`: spaces and `||` need no quoting. It gets no
charset allowlist, because it grants the repository nothing it does not already have over its own
container — only a length cap and one line.

`resources:` is what a repository asks to have **provisioned before its container starts**. The
grammar is flat, because this file has no YAML sequences: `postgresql:<name>[:<database>]` for a
role and database of the repository's own, or the reserved `idp:client` for a qits-idp service
client — comma-separated, one or more of either. Omit a postgres database and it defaults to
`qits_` plus the application name without its `qits-` prefix. Before the cutover, this component
idempotently creates the login role and the database on its tier's postgres and then starts the
container with

```
QITS_RESOURCE_<NAME>_URL       jdbc:postgresql://<tier>-qits-oci-postgresql:5432/<database>
QITS_RESOURCE_<NAME>_USERNAME  <database>        # the role IS the database, one login per database
QITS_RESOURCE_<NAME>_PASSWORD  generated here, stored in pd_resource, never in a file
```

`idp:client` asks qits-idp for a service client instead — created or rotated, never handed a
caller-supplied secret, under the fixed resource name `idp` (reserved: a repository cannot use it
for a postgres resource of its own, since there is one idp per platform and nothing to name). It
starts the container with the same generic shape, three variables instead:

```
QITS_RESOURCE_IDP_URL            http://<env>-qits-idp:8080/idp             # derived, like the postgres host
QITS_RESOURCE_IDP_CLIENT_ID      this deployment's own wire alias (PdNetworks.alias)
QITS_RESOURCE_IDP_CLIENT_SECRET  issued by qits-idp, stored in pd_resource, never in a file
```

The contract is **generic on purpose**: an application maps those variables in its own shipped
config defaults, so this component names no framework and no datasource key for either resource
type. Both are idempotent because they run on every deployment — a redeployment changes nothing, a
reset postgres volume brings the role back with the recorded password, a reset registry rotates a
password or a secret nothing knew any more, and an idp client nobody remembers is recreated rather
than left broken. **Never a `DROP`, and never a rotate of qits-deployments' own idp client** — this
component is adopter #1 of the postgres resource (its own store is a database provisioned this
way), and it deliberately does not adopt `idp:client` for itself yet (rotating itself mid-deployment
would wedge the platform with nobody left to redeploy it).

A repository with **no file** gets every default and behaves exactly as it did before the file
existed. A file that cannot be read or parsed **fails the deployment** with the cause on the row —
never a guess, because a guessed topology is a container on the wrong networks under the wrong name.

## The deployment flow

    green build ──┬─▶ BuildSuccessful on qits-events ─▶ the durable subscriber (the ordinary door)
                  └─▶ POST /deployments/api/events/build-succeeded (manual / bootstrap)
                        (runId, repoId, [projectId, repoName], branch, commitSha)
                          │
                          ▼   the application name is settled here: repoName, or repoId when the
                          │   announcement carried none (which is what the id was before the
                          │   githost's key became an opaque UUID)
                          │
                          ▼   one single-threaded worker; the intake returns 202 immediately
                    read the spec at that sha  ─────────────▶  git host (the ONE outbound call)
                          │                                    /git/<projectId>/<repoName>/blob/…
                          │                                    or /git/<repoId>/blob/… with no pair
                          │
                    register: upsert the service row, link it into the designated entry tier
                          │
                          ▼   one recorded deployment per place it addresses
                    pull ▶ stop the predecessor ▶ run ▶ join networks ▶ health gate ▶ cut over
                          │
                          ▼   back onto qits-events, at each of the four points
                    DeploymentQueued ▶ DeploymentStarted ▶ DeploymentActive | DeploymentFailed

**It announces as well as listens.** Each of the four points publishes an event naming the
deployment, the application, the tier, the commit and the run — and carrying, as its parent, the
`BuildSuccessful` that caused it. So a push, the build it triggered and the container it ended up
in are one walk through the event log rather than three lookups. The vocabulary is the
`qits-platform-deployments-events` jar; a consumer takes that and needs no part of this component.
Nothing is announced twice: an outcome the observer corrects later stays a row.

**Two doors, one flow.** Both announcements meet at the same seam, and nothing below it can tell
them apart. The bus is the ordinary one — the publisher retries it, qits-events' log replays it
after a cutover, and the eventstream library delivers it exactly once whichever channel it came on;
the POST is the manual one, and the only one that works before qits-events exists. Because a
replayed event can be *older* than one already deployed, the subscriber deploys only if the build is
still the newest for its `(repoId, branch)` and logs the skip otherwise. The POST is deliberately
not guarded that way: posting a commit is choosing it.

**The application is named after the repository's NAME.** The git host's repository key is an opaque
storage UUID; the public identity is `(projectId, repoName)`, and the name is what the image tag
`qits/<name>:<sha>`, the wire alias, the container name, the provisioned database and the rollback
pins are all derived from — because every pipeline pushes that literal tag. An announcement with no
name pair falls back to the repository id, which is exactly what the id was before the split.

**The cutover invariant.** A replace is an update of the service the predecessor already is, so
there is no second copy to arbitrate between and no predecessor to hunt for: the name IS the
address. A successor that never goes healthy is reverted by the orchestrator itself
(`--update-failure-action rollback`), which leaves the previous deployment `ACTIVE` and serving —
and under `start-first` it never stopped serving at all. `update_order: stop-first` is the opt-out
for an application that cannot be two processes at once: one binder per published port, one process
per single-writer store. The pull happens first, so replacing the OCI registry's own application
does not depend on it being up mid-cutover.

`publish_mode: ingress` is how a published port stops being a reason to say `stop-first`: the port
is held by swarm's routing mesh rather than by the task, so the successor can bind nothing and
still be reachable while the predecessor serves. The default is `host`, which is the per-node bind
every publishing service has today. The two keys stay independent — this component never derives
one from the other — and the mode is part of a service's shape, so changing it takes a `service rm`
and a redeploy rather than a deployment.

A failed deployment — image missing, the daemon refused, the update never converged — leaves the
world as it was and says why on the row.

**A status is written by the deployment that earned it, and then observed.** Every thirty seconds
(`qits.deployments.observe-interval-seconds`, `0` to switch it off) the **latest** row of
each (application, tier) is read back against the container it names, on the same worker the
deployments run on. A row that says `FAILED` about a container that is running and healthy becomes
`ACTIVE` — with the original failure text kept under the recovery stamp — and a row that says
`ACTIVE` about a container that is absent or terminally exited on **two consecutive** passes becomes
`FAILED`. Restarting and running-but-unhealthy are neither: they are the health gate's own patience,
and a container coming back from the postgres-alias boot race must not be declared dead on the way.
Rows that are not the latest for their place are history and stay untouched, as do `QUEUED`,
`STARTING` and `DECOMMISSIONED`. **The observation writes rows only** — it starts, stops and removes
nothing.

It exists because a status used to be final: one deployment cut its own postgres over, went healthy,
lost every connection it held mid-bookkeeping, and left a row saying `FAILED` beside a container that
served for hours. The connections are retried now; the row needed reading back too.

## Networks are hub and spoke, and docker is the bookkeeping

- an environment application runs on `qits-env-<env>-<app>` — only its own containers are there;
- a **public node** (`available_on_env: true`, today just qits-gateway) additionally joins its
  environment's bundle network and *every* per-application network of that environment. That is the
  hub: cross-application traffic is meant to flow app → gateway → target app;
- a **platform** service runs on `qits-platform` and joins every per-application network of every
  environment, which is what makes it locally reachable everywhere without a gateway route;
- `qits.deployments.legacy-network` (default `qits-net`) is the transition membership
  every container also joins, while the platform still holds direct cross-application URLs. **Emptying it is the
  enforcement flip** — a later phase, after the last direct URL has moved to a gateway route.

**The wire alias — the address peers dial — is `<env>-<app>`, for every application there is.** The
qualifier is what lets two tiers hold one application's address on the legacy network, which they all
share, without either resolving as the other. It had a second spelling, the bare `<app>` of a platform
service, and that went with the plane. Container names follow the same shape:
`qits-pd-<env>-<app>-<id8>`.

**Under swarm that model collapses to ONE overlay**, and deliberately: every `--network-add`
recreates the task, so a membership joined after the fact would turn one deployment into a restart
storm. A service declares its whole membership when it is created — the flat attachable overlay —
and the per-application networks the state machine still computes are dropped, out loud.
`qits-platform` was a second overlay and went with the plane's services that ran on it. **No
membership is ever stored in the database.** It is written as labels under one namespace —
`qits.platform.deployments.` + `environment`, `application`, `deployment`, `available-on-env`,
`app-name`; networks carry `qits.platform.deployments.network=bundle|application` — and read back with
`--filter label=`. One record of the truth, and it is the runtime's. Containers carrying an earlier
spelling (`qits.cd.*`, `qits.pd.*`) count as unlabelled: adoptable, never protected.

## Self-update takes an orchestrator

This component deploys itself, and it cannot stop its own container and then finish the cutover:
the instance that would arbitrate is the one being replaced. That takes a third party, and **swarm
is it** — the manager lives in the daemon, so it stops this task, starts the successor and reverts
the spec if the successor never goes healthy. This instance issues the update on its own service
and dies.

What no orchestrator does is the **row**. The deployment stays `STARTING` until an instance boots
that can settle it, and the startup sweep does that from what is running: the service's image
carrying the row's sha is this deployment serving, another sha is a rollback or a later deployment,
nothing at all is a deployment a restart interrupted.

**A self-update needs an arbiter outside both instances**, and the swarm manager is one: it lives in
the daemon rather than in a container this process owns. The by-hand docker path had no such thing —
it launched a detached referee container, then refused the deployment outright once the referee was
retired — and it is deleted. `qits.deployments.orchestrator` must say `swarm` or the boot
fails.

### Renaming this component is one deploy and one hand step, in both directions

A service name IS its address under swarm and **swarm cannot rename a service**, so a change to the
derivation moves this component's own name — and the self-update path matches on that name, which
means the deploy that moves it is not a self-update at all. It creates the new service beside the old
one, health-gates it, records the row `ACTIVE`, and stops there: `reap` removes nothing under swarm,
because a replace is normally in place.

It has happened twice. Moving ONTO the plane took `<env>-qits-deployments` to bare
`qits-deployments`; deleting the plane takes it back, to `dev-qits-deployments`. Either way two
deployers are then running on one registry and one config volume, and the predecessor goes by hand
once the successor is healthy. `AGENTS.md`'s *Retiring the plane's bare-named services* has the exact
commands for the second move — it is more than a `service rm`, because this time the deployer must not
be allowed to create the successor itself.

Two more things such a move does once, neither of them an error:

- **Both resource passwords rotate.** The registry rows are keyed by `(application, environment,
  resource)`, so a move that changes the key finds no row, takes the reconcile arm and hands fresh
  credentials to the successor — which records them at its first boot. The databases do not move.
- **The old rows stay.** They are leftovers an operator may delete; they collide with nothing.

## What it answers

| Route | Who asks |
| --- | --- |
| `POST/GET/PATCH/DELETE /environments` | the bootstrap, a person through the client |
| `GET /environments/{id}/links` | a reconciliation: this tier's services |
| `PUT/GET/DELETE /services/{name}` | derived registration (in-process); an operator, for the deliberate acts |
| `GET /applications` | the client — one entry per (service, tier), flat |
| `GET /deployments?environmentId=` | the client — one tier's history, newest first |
| `GET /pins` | qits-platform-artifacts' OCI garbage collector, fail-closed |
| `POST /events/build-succeeded` | qits-ci, fire-and-forget; the manual and bootstrap door |

All under `/deployments/api`. The client is served at `/` — this service has a host of its own,
`deployments.<env>.<domain>`, and the segment is the wire surface alone. Health is at
`/deployments/q/health/ready` — which is also, and now exactly, what this component's own
health-path convention derives from its application name.

**The segment was `/platform-deployments` until 2026-09-26, and the old prefix now answers 404.** It
was rewritten onto the live one for exactly one release by `api/LegacyPrefixReroute`, which WARNed
once per request naming the path and the caller, while `routes:` declared both prefixes so the edge
kept routing the old one here. That WARN was the inventory: the last caller on it was this service's
own SPA, the frontend was released with the new path as `2026.926.151155`, and the reroute, the
second `routes:` entry and the `health_path:` override in the spec went together.

**`quarkus.quinoa.ignored-path-prefixes` still lists both, and that entry is permanent.** It went
with the others for one release and had to come back: with nothing rewriting the prefix, every path
under it names no route, and Quinoa's SPA fallback answers a path naming no route with `index.html`
at 200 — so the retired prefix served the client instead of failing, which is the defect this whole
change was about. The live segment is spelled in **four** places, all of them in this repository:
`quarkus.rest.path`, `quarkus.http.non-application-root-path`,
`quarkus.quinoa.ignored-path-prefixes` and `routes:` in the spec; the retired one survives in the
second value of that one key. The health path is derived, not declared — the convention run on the
application name `qits-deployments` gives `/deployments/q/health/ready`, which is the path served.

**The pins are read off deployment rows alone.** qits-platform-artifacts deletes an image tag only
when no pin names it, and deletes nothing when it cannot get an answer, so the keep-set must not
depend on anything the topology says today. Per application name, across every tier: the sha it is
serving, and the sha a rollback would put back.

## Trust boundaries

- **The docker socket is the boundary.** Mounting it hands this container control of the host's
  daemon, which is root-equivalent. It is an explicit deployment act, never baked into the image,
  and the containers this component *starts* never get it.
- **It executes nothing.** The docker vocabulary is container lifecycle — `pull`, `run`, `inspect`,
  `logs`, `rm`, `ps`, `network` create/inspect/rm. `exec` is not in it and must not enter it. What a
  deployed container runs is its image's own entrypoint, untouched.
- **Provisioning speaks SQL, not shell.** A `resources:` line is answered with `CREATE ROLE`,
  `CREATE DATABASE`, `REVOKE` and `ALTER … OWNER` over plain JDBC — no `psql`, no `docker exec`, no
  process at all. The postgres superuser credential comes from **deployment config**, the same trust
  domain that already holds the docker socket, and is never written into a row and never put in an
  argv. There is no `DROP` in the vocabulary and none is coming.
- **Argv contributions come from deployment config and from this component itself.**
  `qits.deployments.extras.<app>.*` is how a stateful application gets its volume, its
  published port, its extra env and the extra DNS names it answers to on the shared network — a
  structured family each driver renders in its own
  orchestrator's words (`ServiceExtras`); the `QITS_RESOURCE_<NAME>_*` triple is generated here and
  injected here. **Nothing PUSHED over HTTP contributes a credential to a `docker run`** — which is
  the same sentence as before, now that a credential is a thing this component holds: what a
  repository can NAME is a database of its own, and the VALUES injected for it are ones this
  component generated. The family may be **pulled** from qits-configuration where a deployment sets
  `qits.deployments.extras-url` — this process reading a named service with its own machine
  identity, which changes the source and not the guard; unset (the shipped state) the config volume's
  file is the whole of it.
- **Untrusted strings are validated at the boundary.** Names become network names, aliases and image
  path segments (dns-label charset); shas become image tags (hex only); the health path is
  interpolated into a string the *container's* shell runs, so it gets the strictest allowlist and is
  re-checked at the last line before the argv. A `health_cmd` is the one deliberate exception — it
  *is* the shell string, chosen by the repository for its own container — and is bounded to one
  non-blank line rather than a charset.
- **`resources:` gets the health-path treatment, and one checkpoint more.** Both halves are
  repository-authored: the resource name becomes an environment-variable key in a `docker run`, and
  the database name lands in **DDL against a postgres instance the whole platform shares** — where
  there is no bind variable to fall back on. So both are allowlists (`[a-z][a-z0-9-]{0,31}` and a
  mandatory `qits_` prefix, which structurally excludes `postgres`, `template0/1` and every `pg_*`),
  checked at the parser, again immediately before the SQL is assembled, and again at the argv.
- **The `platformdeployments` database is credential-bearing now.** `pd_resource.password` is the
  single authority for every provisioned application's credential — no file carries it, the
  bootstrap does not record it. Treat that database with the sensitivity of the
  `qits-deployments-config` volume, which already holds the push token and the idp secrets. No
  statement containing a password is ever logged, and no failure message names one.
- **Every endpoint carries a role, and the role says who the caller is meant to be.** The reads
  need `qits:admin`, which reaches this service only as a forwarded header — an admin
  session through the edge, or the bootstrap's own hop. The pins, the deployment-request listing,
  the topology writes and the build-succeeded intake need `qits:system`, which qits-platform-idp
  puts in a machine token's `groups` claim — the open calling model's "a service calling a service"
  role. The two sets — `qits:system` and `qits:admin` — do not overlap: no token holds both, so a
  browser session cannot write the surface and a machine bearer reaches only the two reads granted
  to it by name. Those two are the pins, which qits-platform-artifacts' collector plans a sweep
  from, and `GET /deployment-requests`, which qits-projects draws a release's deploy phase from;
  everything else a read answers is a person's. The writes and the intake additionally call `MachineAuth.require()`
  (audience `qits-platform`, the platform's one), behind a gate that ships **off** —
  `QITS_AUTH_MACHINE_REQUIRED=true` turns it on, only once the senders are sending.

## Building it

    git clone … && cd qits-deployments-platform-service
    git submodule update --init          # the web client
    ./mvnw clean verify

A clone of this repo alone builds and tests green — no monorepo, no docker, no credentials. The two
seams that reach outside the process are faked in the suite. `./mvnw test` needs neither node nor
the client; `./mvnw verify` reaches `package`, where Quinoa augments, and needs both.

`AGENTS.md` carries the working conventions.
