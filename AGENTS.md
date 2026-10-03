# qits-deployments — working notes

Read `README.md` first: it defines the model (tiers, two planes, derived rows) and the flow (green
build → registration → health-gated cutover). This file is the working conventions on top of it.

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials, **no network**. That is why the poms duplicate versions
instead of inheriting them, and why every seam that reaches outside the process is faked rather than
skipped: `FakeDeploymentDriver` behind `DeploymentDriver` (the orchestrator), `FakeSpecSource` behind
`SpecSource` (the git host), `FakeResourceProvisioner` behind `ResourceProvisioner` (the
platform's postgres), `FakeDeclarationSeed` behind `DeclarationSeed` (qits-configuration's
declaration intake) and `FakeIdpClientProvisioner` behind `IdpClientProvisioner` (qits-idp's
service-client API). **Five fakes** — the ancestor's stub HTTP server for the topology dissolved
when the topology became a repository query, the fourth arrived with the seed, and the fifth with
the second resource type.

**One seam, `DeploymentExtrasSource`, has no `@Mock` fake and that is deliberate** — it
returns a `Config` rather than holding a conversation, so a test states one in a lambda. Nothing
about it reaches the network in the shipped state either: `qits.deployments.extras-url` is
unset, so the suite reads the same config it always read. See the extras section below.
**`DeclarationSeed` is the same peer and takes the opposite shape**, which is the distinction worth
keeping: it POSTs a body and is answered with a decision, and the two decisions end a deployment
differently — a thing to script and to read back, which is a bean rather than a lambda.

The one thing the suite does start is a **postgres of its own**: the component's store is one now,
and `testdb/EmbeddedPg` spawns zonky's real binaries as a child process. A maven dependency, not a
container — the rule is no docker, and it still holds.

**Which command is the gate depends on whether you have the client** (`git clone … && git submodule
update --init`):

- `./mvnw test` — needs **neither node nor the webui submodule**. Quinoa is disabled by default in
  test mode, so every `@QuarkusTest` here passes against an empty `webui/` on a machine with no node.
- `./mvnw verify` — runs `package` on its way to failsafe, and `package` is where Quinoa augments. So
  verify needs **both**, and against an uninitialised submodule it fails with
  `No package.json found in Web UI directory: 'src/main/webui'`.

Always `clean verify`, and the suite takes a free port —
`service/src/test/resources/application.properties` sets `quarkus.http.test-port=0`, because on the
deployment host 8081 is the platform's own npm registry and `@QuarkusTest` restarts race for it
anywhere. Failsafe passes the same 0 to the packaged artifact.

**`service/` compiles to a GraalVM native image.** `.sdkmanrc` names `25.0.2-graalce`, so `sdk env`
gives you a `native-image` and `./mvnw verify -Dnative` produces
`service/target/qits-platform-deployments` and runs `PdPackagedSurfaceIT` against it. Consequences to
keep in your head: a missing GraalVM does not fail the build (Quarkus falls back to a container build
— grep the log for `Cannot find the native-image`); prefer what is already in the image
(`ProcessBuilder` over a docker client library — the reason `PdProcess` shells out); every config
default the app boots with is part of the native surface (the AUTO_SERVER lesson, which now reads as:
the datasource ships an *expression* over `QITS_RESOURCE_DB_*` and no fallback URL at all, so there
is no default with a feature in it to lose); and anything returned as `Response.entity(...)` is invisible to the build-time
Jackson analysis, which is what `api/ApiWireReflection` exists for. **A new response type joins that
list in the commit that adds it** — the failure is a 500 in the native binary while every JVM test
stays green, and it has been paid for once already.

**There is a second such list now, `bus/EventWireReflection`, and it exists for a different
reason**: `CanonicalJson` builds its own `ObjectMapper` on purpose — the payload string is a
byte-for-byte wire contract and a consuming application's customizers must not reach it — so the
whole graph it binds is invisible to the same analysis. It registers the consuming path
(`EventFrame`, the package-private `EventPage` by string name, and the payload record). Leave
`EventPage` out and the stream works in the binary while **catch-up alone** fails, which is the half
nobody would be watching. **The publishing path joined it when this component got events of its
own**: the four `DeploymentQueued`/`Started`/`Active`/`Failed` records, `EventEnvelope`, and
`CanonicalJson$QitsEventMixin` by string name because it is nested in the library. The mix-in is the
quiet one — it is what keeps `eventId` out of the payload, so its absence is a wire contract that
changed with no crash and no log. **A fifth event joins the list in the commit that adds it.**

## The partition, and the one rule that keeps it

Four maven modules, package root `eu.wohlben.qits.deployments`:

- **`environments/`** (`…environments.*`) — the topology: `entity`, `persistence`, `dto`, `mapper`,
  `control`, `error`. `EnvironmentService` (tier rows), `ServiceCatalog` (services, links and the
  three rules over them), `PdIdentifiers` (what the topology stores), `PdNetworks`,
  `ApplicationKeys`. **It also owns the datasource, the persistence unit and the Flyway lineage** —
  one database, declared once, in the module both others depend on.
- **`deployments/`** (`…deployments.*`) — the execution: `DeployService`, `EnvironmentOperations`,
  `RollbackPins`, `DeploymentSpecParser`, `DeploymentIdentifiers` (what only reaches an argv),
  `ImageRefs`, `ContainerNames`, `PdProcess`, `ResourceProvisioning` and `BootResourceRegistration`,
  and the six seams `DeploymentDriver` / `SpecSource` / `ResourceProvisioner` /
  `DeploymentExtrasSource` / `DeclarationSeed` / `IdpClientProvisioner` plus the announcement port
  `ReleaseAnnouncements` and the ordering
  collapse `ReleaseTips` behind it (with `Versions` and `PackageNames`), and the outgoing port
  `DeployAnnouncer`.
- **`deployments-events/`** (`…deployments.events.*`) — the event VOCABULARY: four plain records
  over `qits-eventstream` and nothing else, not even quarkus-arc. It is a module rather than a
  package because a vocabulary is what a *consumer* needs: the day another service listens for
  `DeploymentActive` it takes this jar and gets the record plus the bus and no part of the deployer.
  The ci-events and githost-events shape, which is also why the directory carries the repo's name
  rather than a bare role word.
- **`service/`** (`…api`, `…bus`, `…swarmhost`, `…githost`, `…pghost`, `…confighost`, `…idphost`) —
  the adapters. `idphost` is the youngest, and it presents a credential too — this component's own,
  to qits-idp's service-client API, over a plain JDK `HttpClient` rather than a named oidc client:
  the call is Basic-authenticated, not bearer-shaped, since it is provisioning a PEER's credential
  rather than fetching a token for itself. `confighost` reads qits-configuration's resolved extras
  and holds the named oidc client that does that one. `bus` is the
  event-bus half: the durable `SoftwareRelease` subscriber, the `DeployEventAnnouncer` that
  publishes this component's own four events, and the native-image registration for what the
  library's own `ObjectMapper` binds. Identity is not a package here: the forward-auth pair
  lives in the published `qits-auth-core`.

**The `platform` qualifier is GONE from the package root**, with the plane it named: it is
`eu.wohlben.qits.deployments` now, so the execution module lands at `…qits.deployments.deployments`
next to `…qits.deployments.environments`. The repetition was the price of a qualifier naming the
plane and is now just the price of a module called `deployments` inside a component called
`deployments` — the pairing with `environments` is what it buys, and renaming either to dodge it
would cost that. **The REST path has since followed**: it is `/deployments/api` as of 2026-09-26,
and the retired prefix is answered by nothing — the one-release `api/LegacyPrefixReroute` that
rewrote it is deleted. See *Addressing and auth*. The artifactIds (`qits-platform-deployments-*`)
and `quarkus.package.output-name` did NOT follow and are deliberately untouched: those are published coordinates that the Dockerfile's `COPY`
line and `service/pom.xml`'s native image path agree on, and neither is an address anybody dials.

**`deployments` depends on `environments` and never the reverse.** That is the partition, and it is
the thing to defend. Execution reads and writes the topology; the topology knows nothing about
containers. The concrete consequence is `EnvironmentOperations`: creating a tier is a row
(`environments`) *and* a network (`deployments`), so the composition lives on the execution side and
`EnvironmentService` stays socketless. Do not put a driver call in `environments/`.

**The seam rule is one rule, applied six times.** Everything the domain modules cannot do — shell
out to docker, fetch a file over HTTP, speak DDL to somebody else's server, hand a file to a store —
is an interface there and an implementation in `service/`, with a scripted double in the suite.
`ResourceProvisioner` was the third; `DeploymentExtrasSource` is the fourth and took the shape
unchanged; `DeclarationSeed` is the fifth, and it is the first that WRITES to a peer. **`IdpClientProvisioner`
is the sixth, and it is the second that writes to a peer** — qits-idp is a credential authority
exactly like postgres (below), so its seam takes the postgres shape (a domain interface, an
adapter that speaks the peer's own protocol) rather than the extras/declaration shape (a bearer
over a peer this component also READS from). Do not put a client in a domain module.

**The extras seam's double is a lambda, not a `@Mock` bean, and that is the seam's own shape.**
`DeploymentExtrasSource` is a `@FunctionalInterface` returning a `Config`, so a test that wants the
file behaviour writes `application -> ExtrasSnapshot.over(boot, file)` and a test that wants a
served one writes the map. There is no `FakeDeploymentExtrasSource` to reset, because nothing here
is a conversation to script. What IS scripted is the HTTP — `confighost/ExtrasStub`, the JDK's own
server on a real socket — and only for `ConfigHostExtrasSource`'s own tests, where the request is
what is under test: the url it is built at, the headers it carries and the patience it spends. A
fake at the seam there would assert this suite's model of a client.

**`DeclarationSeed`'s double IS a `@Mock` bean, and the asymmetry is the argument rather than an
inconsistency.** That seam holds a conversation: a body goes out, a status comes back, and a 409 or
a 422 ends the deployment differently from a 503 that outlasted the budget. A test wants both
directions — script the answer, then read back what was sent, the bytes included — and a lambda
gives neither. So `FakeDeclarationSeed` sits beside the other three, reset in `@BeforeEach`, while
`confighost/ExtrasStub` grew a declarations arm and keeps holding the HTTP half for
`ConfigHostDeclarationSeedTest`, where the request itself is what is under test.

## What the merge dissolved (do not bring it back)

The topology was `qits-serviceregistry` for one release, reached over HTTP. All of the following is
**gone on purpose**, and each of them is a thing an agent might reasonably try to re-add:

- **`RegistryClient` and `HttpRegistryClient`.** Registration writes rows in the same transaction;
  resolution is a repository query. `ServiceCatalog` and `EnvironmentService` are called directly.
- **`RegistryBearer`** — the class. **The `quarkus-oidc-client` block came BACK**, and this entry is
  the promise being kept rather than broken: "if this service ever calls a guarded peer again, all
  three arrive in that commit". qits-configuration is that peer, and all three arrived —
  `confighost/IdpExtrasBearer` over a **named** client, the shipped-off switch
  (`quarkus.oidc-client.configuration.client-enabled=false`) and a secret a deployment supplies.
  What stays gone is the topology client it used to serve. **The peer count is one**: a second named
  client is a second peer, and a second peer wants the argument this one made.
  **qits-idp is a peer too now, and it does not break the count** — it is reached a different way
  (`idphost/HttpIdpClientProvisioner`, Basic-authenticated with this component's own client id and
  secret, no named oidc client of its own) because it is a different kind of peer: qits-configuration
  is read from and written to as CONFIGURATION, over a bearer this component fetches for itself;
  qits-idp is a CREDENTIAL AUTHORITY this component provisions OTHER applications' secrets from, the
  same relationship it already has with the platform's postgres (below) — provisioning speaks the
  peer's own protocol, not a token exchange. A third peer reached the extras way would still want the
  argument above; a second one reached the postgres way is the shape a credential authority takes.
- **`StubRegistry`**, the `@WithTestResource(GLOBAL)` server every `@QuarkusTest` carried.
- **The registry-outage posture** — `RegistryException` (502), `lastKnownTargets` (the
  deployment-history fallback), `CdRegistryOutageTest`. There is no outage to have a posture about.
  The **spec read** keeps its posture exactly as it was, because it is still a remote call.
- **`RegistryExport`**, the one-time boot seeding of the registry from local tables, and the frozen
  `cd_environment`/`cd_application` tables it read. Clean start: one V1, no lineage inherited.

What survived from that seam is the *claims*, rewritten against the local domain:
`PdRegistrationTest` holds what a green build writes and reads back; `PdPinApiTest` holds that the
pins read nothing but deployment rows, which was the outage suite's one claim that was never about
the peer.

**The hazard the merge created, and it bit once already.** The topology is now read from the deploy
**worker** — a bare daemon thread with no request context and no transaction — where the ancestor
made an HTTP call that needed neither. Hibernate throws `ContextNotActiveException` there. So every
read on `ServiceCatalog` and `EnvironmentService` brackets itself with
`QuarkusTransaction.joiningExisting()`: joining rather than requiring a new one, so a caller that
already has a transaction keeps its entities managed. **A new read method on either class needs that
bracket**, and a `@QuarkusTest` that only drives the REST surface will not catch its absence — the
request context hides it. `PdDeploymentFlowTest` is what catches it.

## The worker

`DeployService` runs **the whole of a software-release event** on a single-threaded daemon worker
(`pd-deploy-worker`), the `CiRunService` shape: the intake validates and returns, each DB transition
sits in its own `QuarkusTransaction.requiringNew()` bracket, and everything the docker calls need is
copied out of the entities into a plain `Plan` record first.

Serial execution is load-bearing twice over:

- it makes "the previous ACTIVE deployment" an uncontended read during cutover;
- it makes derived registration's read-then-write atomic against every other event. `ServiceCatalog.
  upsert` is `synchronized` as the belt for every other caller, and the unique service name is a
  third — but the worker is what makes the *pair* (read the links, write the union) atomic, and
  neither of the other two covers that. `twoIdenticalEventsArrivingTogetherRegisterOnePlatformService`
  holds it.

The cost is that the spec's HTTP read sits in the queue too;
`qits.deployments.git-host-timeout-seconds` bounds it. `awaitIdle()` is public for the suite, because "nothing was registered" can only be
asserted after the worker has had the event.

Transactions are programmatic everywhere in `control`, never `@Transactional` — partly for the
worker, partly because a `this.`-invocation never crosses the interceptor and a lost bracket fails
quietly.

The startup sweep (`DeployService.onStart`) settles rows left `QUEUED`/`STARTING` by a crash — see
"One orchestrator, one seam" for what settles them — and **deliberately reaps no containers**: a
deployed application outlives its deployer, and whatever was ACTIVE before the restart is still
serving. Do not "complete" the sweep with a reap.

**A second boot pass settles the `SPEC_UNREADABLE` rows a dead process was holding** (2026-09-07),
and it is the same stranding one word further on. Those rows are held by `DeployService.specRetries`
— memory, on purpose — so a process that dies mid-hold leaves them in flight with nobody left to
re-read them, and `SPEC_UNREADABLE` is on `RequestLifecycle`'s in-flight list, so the SPA draws a
pending deployment forever. Measured: `qits-ci@2026.907.184918` and
`qits-deployments@2026.907.192519` sat there for over two hours across several deployer restarts
while both versions were serving off re-fired events. `sweepHeldSpecReads()` runs from `onStart`
immediately after `sweepInFlight()` — still before `OwedReleaseSweep`'s `APPLICATION + 600` observer,
so both predecessor cleanups finish before anything queues a fresh row. Four things about it:

- **It settles only rows no in-memory hold carries**, which at boot is all of them. That is the
  contract rather than a precaution: driven beside a live retry it must not settle a release
  `retrySpecReads()` is about to re-read.
- **The place is already serving → `SUPERSEDED`.** An `ACTIVE` row of the same (application, tier)
  with a later `seq`, or one carrying the same or a newer `version`, answers the question the held
  row was waiting on. The version arm is asked **only when both rows carry one**, so a pre-V7 row
  tagged with a sha never answers a question about a CalVer stamp, and `PdDeployment.imageTag()`
  stays the single reader of the tag. This is the sweep's second writer of that word.
- **Nothing serves it → `FAILED`, and re-entering the hold is not an option that exists.** A
  `SpecRetry` needs a `RepositoryRef`, a package name and a `Door`; `pd_deployment` records an
  application NAME and no repository identity at all, by V1's own header. And the durable recovery is
  already one layer out — a release held on an unreadable spec is deliberately left owed in
  `pd_owed_release`, and `OwedReleaseSweep` re-drives it through the ordinary door. A hold rebuilt
  here would be a second announcement path for the same release racing that one on the same worker.
  The `MANUAL` door writes no obligation on purpose, so for a hand-announced version `FAILED` is the
  whole answer.
- **Neither arm announces.** `recordUnreadableSpec` already announced `DeploymentFailed` for that row
  through `finish`, so both arms go through `finishHeld` — which now takes the status, keeps its
  "only if still `SPEC_UNREADABLE`" guard, and says nothing. Settling through `finish` would tell
  every consumer the deployment failed twice.

`PdHeldSpecSweepTest` holds all four, driving `sweepHeldSpecReads()` package-locally the way
`PdSweepAdoptionTest` drives `sweepInFlight()`.

**The worker survives losing its own datasource, in exactly three brackets.** This component deploys
qits-oci-postgresql — the postgres its own registry lives in — so cutting that container over kills
every connection the deployment performing it is holding. It did: eaa34fbc cut over cleanly, went
healthy, and then ended `FAILED: [unexpected: JDBCConnectionException …]` because the post-gate
bookkeeping ran on dead connections, while a second event was dropped the same way in the worker's
own catch. `DbRetry` wraps the catalogue read an event opens with, the cutover bookkeeping and
`finish` — **connection-class failures only** (SQLState `08*`/`57P0x`, the pool's acquisition
timeout, Hibernate's `JDBCConnectionException`), thirty seconds of half-second sleeps, safe because
the worker is single-threaded and those three brackets re-read what they write.

**`DbRetry` is the platform's now** — `eu.wohlben.qits.db.DbRetry` from `qits-db-core`, published by
qits-integrations-quarkus-javalib. It was a private class here first, and the lib's is that class
with the budget moved from a constant to a per-call argument; the thirty seconds are stated at each
call site as `DeployService.CUTOVER_BUDGET` (package-private, because `DeploymentObserver` wraps its
brackets for the same reason and one budget spelled twice would drift). The lib's own suite pins
every failure shape this component ever saw, which is why the local `DbRetryTest` went with the
local class.

**It has two spellings and the choice is not a style one — it is who owns the transaction.**
`DbRetry.call` wraps a block; `DbRetry.inNewTx`/`runInNewTx` **is** the `requiringNew`. Owning the
boundary is what lets the retry tell "the body threw it, so it certainly never committed" from
"the transaction manager reported it", which is the one round trip nothing can place — Narayana
spells a lost commit and a real rollback with the same `RollbackException`, measured. So:

- a **read** bracketed by the callee (`catalog.find`, `environments.onBranch`) keeps `call`;
- a **write** that used to read `DbRetry.call(…, () -> requiringNew().call(…))` is now `inNewTx`
  with the `requiringNew` gone — the worker's cutover bookkeeping and `finish`, and all three of
  `DeploymentObserver`'s brackets;
- and each of those bodies ends with a `flush()`. An ORM flushes at commit by default, which would
  put every statement on the far side of the undecidable round trip; flushed, a lost connection is a
  body failure and is retried. Without the flush the wrap reports rather than helps.

`DbRetry.call` around a write survives in exactly one place, `ServiceCatalog.upsert`, and its
javadoc argues why: the boundary there has to stay inside a `synchronized`, so what makes a second
attempt safe is the write's own converging shape rather than the retry's knowledge.

**It is the second half of a pair, and the first half is the pool.** The datasource carries the
platform's three-line baseline — `jdbc.driver=eu.wohlben.qits.db.PatientPgDriver`,
`validate-on-borrow=true`, `acquisition-timeout=15S` — so a connection request is held while postgres
comes back rather than failing at once. `DatasourceBaselineTest` (qits-arch-rules, beside
`ArchRulesTest` and in `service/` for the same reason) fails the build naming any postgresql
datasource missing a line. That includes the `eventstream` one, which the pinned qits-eventstream
release ships bare: `service/`'s `application.properties` states the three lines for it, and that
block is marked to delete when a release of that jar carries them itself.

**What is deliberately NOT retried, and it is a rule rather than an omission:** `queue`,
`recordRejection`, the `STARTING` transition and the platform conversion. They insert or move rows,
so a commit whose outcome the connection died before reporting would be duplicated by a second
attempt — and all of them run before anything docker-side has happened, so losing one drops the
event with nothing half-done. Retry what comes *after* a container is running, where dropping the
work leaves a live container with no row that admits it. Never a business failure: a 409 retried is
one visible failure turned into a slow one.

**The REST reads are patient too, and the wrap is in the CONTROLLERS.** `PdReadPatience` (in `api`)
spends `qits.deployments.db-retry-deadline` — 15S shipped, not the worker's 30 — on every
read of this surface: the service listing, the applications, the environment listing and aggregate,
the link query, and the deployment listing's tier check. **A new read endpoint joins it in the
commit that adds it; no write ever joins it** — a write's patience lives one layer down, below.

It is a bean the controllers call rather than a wrap inside `ServiceCatalog`/`EnvironmentService`,
and the reason is that those reads have callers that must not sleep. `ServiceCatalog.delete` calls
`require`, `allApplications` calls `list`, `EnvironmentService`'s `update`/`delete` call `require`,
and `ReleaseTips` reads the request rows from inside a `requiringNew` bracket under `claim`'s
`synchronized`. A retry inside the read would sleep holding a transaction, and there
holding a monitor. The worker's own reads are already wrapped at `CUTOVER_BUDGET`, so a wrap inside
would nest one budget in the other. (`ServiceCatalog.upsert` is `synchronized` as well, but it is a
write and no read shares its monitor.) `PdReadPatienceTest` holds both halves — recovered after one
lost connection, still a 500 when the database stays gone — off a stand-in repository installed with
`QuarkusMock`, under `DbPatienceShortProfile` so the deadline is reachable in a suite.

**The request-path WRITES are patient too, and their wrap is in the SERVICE — because it has to be
the transaction.** `inNewTx` only knows an attempt never committed if it owns the boundary, and the
boundary is in `EnvironmentService`, not in a controller. So `create`, `update` and `delete` each
*are* a `DbRetry.inNewTx` spending the same `db-retry-deadline` the reads do (a request thread, not
the worker's 30S), with validation left outside it — a rejected name is not worth a second attempt —
and a `flush()` as the body's last statement. `PdWritePatienceTest` holds both halves: an insert
whose connection dies *after* it ran lands **exactly once** on the second attempt, and a failure
that is not the connection is reported on the first.

`ServiceCatalog.upsert` is the one write wrapped from outside instead, and the two reasons are worth
keeping straight. It is `synchronized`, and a retry inside would sleep holding the catalogue's
monitor; the monitor has to enclose the commit, because the lock is what makes "is there a row for
this name yet" atomic. So the wrap sits on the REST door — the non-`synchronized` `upsert(Upsert)`
that already exists for causation — and it is `DbRetry.call`, safe because an upsert by name
converges rather than because the retry knows anything. **The worker's door `upsert(Upsert, UUID)`
stays bare**, with `queue`, `recordRejection` and the rest of derived registration: all of it runs
before anything docker-side has happened, where losing an event leaves nothing half-done.

## The observer: the second half of the eaa34fbc story

`DbRetry` fixed the **cause** above. It did nothing for the row: eaa34fbc still says `FAILED` while
`qits-pd-prod-qits-oci-postgresql-eaa34fbc` has been `Up (healthy)` for hours holding the
`prod-qits-oci-postgresql` alias, because a status was written once at deploy time and never read
back. `DeploymentObserver` is that second half, and the mirror image it also closes: an `ACTIVE` row
whose container died an hour after the gate passed, with nothing ever noticing.

- **It runs on the deploy worker**, enqueued by a bare daemon ticker (`pd-observation-ticker`) every
  `qits.deployments.observe-interval-seconds` (30; `0` is off). Not quarkus-scheduler: the
  ticker's whole job is `worker.submit`, and a scheduler extension would put a second concurrency
  model beside a component whose entire ordering story is "one worker, in queue order". An observer
  thread of its own would take away the invariant serial execution buys — "the previous ACTIVE
  deployment is an uncontended read" — and could read the state between a cutover's own brackets. A
  tick that fires while one pass is already pending **collapses** into it (`observationPending`): an
  observation is a statement about now, so ten of them stacked behind a long deploy queue would all
  answer the same question.
- **It settles the LATEST row per (application, tier) only**, latest by `seq`. History stays history:
  an older `FAILED` row describes an attempt that really did fail, and today's healthy container says
  nothing about it. `QUEUED`/`STARTING` belong to the worker's state machine and to the startup sweep
  — a self-update sits in `STARTING` with a healthy successor **on purpose** —
  and `DECOMMISSIONED` is another deployment's decision.
- **`FAILED` or `GONE` → `ACTIVE`** when the container **the row itself names** is healthy by
  `HealthGate.healthy` (the gate's own verdict, extracted so there is one spelling of it). Only the
  row's own container: the seam asks by container **name**, never by alias, so a healthy container of
  somebody else's deployment cannot resurrect a foreign row. The detail **appends** — the original
  failure text is the diagnosis and is what made the bug findable in the first place.
- **`ACTIVE` → `GONE`** only when the container is **absent or terminally exited/dead**, and only
  when **two consecutive** passes agree. Both halves are the health gate's patience restated:
  restarting is not dead, running-but-unhealthy is not dead (that is the postgres-alias boot race the
  gate already tolerates), and one `docker inspect` that could not answer must not flip a deployment
  that is serving. The strike count is in memory on purpose — it is a debounce, not a fact, and a
  restart that loses it spends two more passes agreeing.
- **A recovery also decommissions the prior `ACTIVE` rows of that place.** The bookkeeping that died
  in eaa34fbc was one bracket doing two things, so a recovered row often has a predecessor still
  claiming to serve, and two `ACTIVE` rows for one (application, tier) is the invariant
  `listActiveByApplication` and the rollback pins are written around.
- **It writes rows and nothing else** — no container started, stopped or removed, no network touched.
  The sweep's "deliberately reaps no containers" stance, and it applies more strongly here: the sweep
  runs once at boot, this runs forever beside a live platform. Whatever still holds an alias is
  absorbed by the next deployment's predecessor search, which is where that decision belongs.
- The reads and the writes are `DbRetry`-wrapped for the same reason the cutover bookkeeping is: this
  is bookkeeping *after* a container is running, and one day a pass will run during a postgres
  self-cutover. The docker call sits between the two brackets, never inside one.

- **A place the orchestrator DECLARES empty is `SCALED_TO_ZERO`, not `GONE`, and there is no
  debounce on that arm.** See *Scale and restart* below: the desired replica count is asked only of
  a candidate that already looks dead, so a healthy platform pays nothing for it, and only a
  confident zero counts — a runtime that cannot answer still demotes, because an unreadable answer
  read as a deliberate stop would turn every outage into a pause nobody is paged for.

No ticker runs under a `@QuarkusTest` — `onStart` returns early in test mode — so the interval keeps
its shipped default in the suite and `PdDeploymentObservationTest` drives `observeOnce()` and
`enqueueObservation()` directly, the `PdSweepAdoptionTest` shape. That test also holds the serialization
claim, off the fake's call log: the pass's `observe:` calls land after the deployment's last one.

## Scale and restart: the operator's two levers (2026-09-04)

**qits-ci wedged and the only recovery was a same-sha push.** A disk-full incident orphaned its
in-memory dispatch queue — a state its own boot sweep clears in seconds — and the platform's one
lever for replacing a container was re-firing `environment/dev` so a rebuild and a redeploy would do
it: a quarter of an hour, a new deployment row and a fresh image build, to restart a process. So a
restart is an operation here now, and so is stopping an application:

    POST /deployments/api/applications/{applicationId}/scale   {"replicas": 0|1}   → 202
    POST /deployments/api/applications/{applicationId}/restart                      → 202

Eight things about it, each easy to undo by accident:

- **`applicationId` is the derived key the read surface already carries** — `<environmentId>:<name>`,
  `platform:<name>` — and `ApplicationKeys.parse` is its one inverse. The derivation was one-way for
  as long as the id was only ever a join key; a door that ACTS on an application has to turn it back
  into the `(application, tier)` pair the rows are keyed by.
- **The service the update is issued against comes off the deployment ROW** (`container_name`, which
  under swarm is the service's name and therefore its address) — never from the request. That is the
  same source `DeploymentObserver` takes it from and the same rule: nothing arriving over HTTP may
  shape an argv, and only the service a row named may be acted on for that row.
- **Everything runs on `pd-deploy-worker`** (`DeployService.enqueueOperation`), which is a
  correctness requirement rather than tidiness. A scale and a bounce are both
  `docker service update` on a service a deployment may be cutting over this second, and swarm's
  `UpdateStatus` holds the most recent update — an operator's restart issued from a request thread
  mid-cutover would be the status `awaitConverged` reads as that deployment's verdict. Hence 202,
  and hence the suite waits on `awaitIdle()`.
- **A restart writes NO status.** It stamps the row's `detail` and nothing else — the id, the sha,
  the container name, the timestamps and the history are untouched. That stamp is the whole audit
  trail, and it is deliberately not a row: a bounce is not an attempt to put a commit live. A new
  stamp **replaces** a previous one and keeps everything under it, so a script hammering the door
  cannot grow a text column and the deployment's own diagnosis is never lost.
- **A scale to 0 writes `SCALED_TO_ZERO`; a scale back up writes nothing** and leaves the row for
  the observation to promote. This component reaches a health verdict in exactly one place and it is
  `DeploymentObserver`; a scale that wrote `ACTIVE` would be claiming a gate it never ran. The wait
  is one `observe-interval-seconds` and that is the honest cost.
- **`MAX_REPLICAS` is 1, and it is the platform's shape rather than swarm's limit.** Every
  application here binds host ports from inside the task, or writes to a store with one writer, or
  holds a config volume. Raising it belongs in the commit that makes one of them able to run twice.
- **Scaling the deployer's own service to 0 is REFUSED by the driver**, naming why: there would be
  nothing left to scale it back up, and the API that would have done it is what stops answering.
  Scaling it up and bouncing it are allowed and come back `HANDED_OFF`, the self-deployment's own
  outcome and the same arbiter.
- **A deployment RESTATES `--replicas 1`**, which is new in `buildUpdateArgv` and is the one piece
  of desired state an update owns beside the image. A service left at 0 takes `service update
  --image` happily — swarm has no task to converge, so the update completes at once and the row is
  recorded `ACTIVE` with nothing running behind it. Mounts, networks and ports stay absent for the
  reason they always were: those are SHAPE, and this is state.

**The role is `qits:admin`, the reader's**, and that is a decision rather than an
oversight: this is a person's operational action driven from this component's own client through the
edge's forwarded header. `qits:system` is deliberately not granted — nothing on the
platform should be able to stop an application as a side effect of holding a service token, and the
two sets do not overlap. `MachineGuardEnforcedTest` arms it in both directions.

**Neither door announces an event**, and that is `DeploymentObserver`'s rule rather than an
omission: the four events describe a *deployment's* lifecycle, and a bounce is not one. A consumer
told `DeploymentActive` twice for one row would have to know the second statement supersedes the
first, which is a second design and is not this one. The routing snapshot is unchanged by either
action, so the edge has nothing to relearn.

**What is NOT on the read surface, and it is a decision.** There is no live replica count per row.
The status word carries the whole of what a client needs (`SCALED_TO_ZERO` is not `ACTIVE`, so a
stopped service does not render as a healthy one), and a `replicas` field would mean one
`docker service inspect` per row per listing — a read surface that shells out per row is a listing
that fails when the daemon is busy.

## Decommission: the third lever, and the one that writes no docker call (2026-09-04)

**The `application:` section below says "CHANGING the value later is a decommission and a new
application, and nothing here helps". This is the "here".**

    POST /deployments/api/applications/{applicationId}/decommission   → 200

A repository that is renamed, or that starts overriding its deployed identity, leaves its old
application name behind holding whatever its last attempt wrote. Three of them sat on this install —
`qits-workspaces-service`, `qits-projects-service` and `qits-gateway` — each with a red `FAILED` row
from August as its *current state*, beside the successor that was plainly serving. The listing was
telling the truth about a row and lying about the platform.

Six things about it:

- **It writes a word; it deletes nothing.** `DECOMMISSIONED` is this component's own vocabulary for
  "this deployment's place was taken", written by `DeployService` on every cutover; a retirement is
  the same statement with an *application* as the successor rather than a deployment. The shas, the
  run ids, the timestamps and the failure text stay exactly as they were. There is no delete on this
  surface and there should not be: `RollbackPins` reads history, a cached id still resolves, and a
  retirement is not a reason to lose an audit trail.
- **The CURRENT row and the `SPEC_UNREADABLE` rows, and nothing else.** The current row because it
  *is* what the listing calls the application's state. `SPEC_UNREADABLE` wherever it sits, because
  it is the one word here that is not terminal — `DeployService` re-reads it on the observation's
  cadence until the git host answers, and a retired application's spec never will. Older terminal
  rows keep the word they earned: six identical `DECOMMISSIONED` rows would have destroyed the
  history the door refuses to delete.
- **200, not 202, and it is the only write on this class that is.** It issues no orchestrator call
  at all — there is nothing running to act on, which is the *precondition* — so there is nothing to
  queue behind the deploy worker and nothing to promise. A door that grew an `awaitIdle()` in its
  test has grown a queue it has no reason to have.
- **It refuses an application that is still deployed.** `ACTIVE` or `SCALED_TO_ZERO` on the current
  row is a 409: the id names a place by string and a typo names a live application just as well as a
  dead one. `SCALED_TO_ZERO` is in that pair deliberately — the swarm service exists, holds its
  ports and volumes, and one scale back up makes the row `ACTIVE` again. `QUEUED`/`STARTING` is a
  409 too: those are the worker's words and it is about to write the next one.
- **It does NOT touch `pd_service`.** The catalogue is derived and `DELETE /services/{name}` is the
  operator's deliberate act on it — a shipped door with its own argument. Folding it in would have
  this one quietly turn a retired application into a row the client labels "no longer tracked",
  which is a worse row than the one it was asked to fix.
- **The stamp joins the other two.** `ApplicationScaling.stamped` drops a leading operator stamp so
  a door called twice cannot grow a text column; `[decommissioned by ` is on that list, which is
  what makes the retirement re-runnable. There is one stamp format on a deployment row and one place
  that knows it.

**Role: `qits:admin`**, for the reason the other two levers carry it — a service bearer must
no more be able to declare an application retired than to stop one. `MachineGuardEnforcedTest` arms
it in both directions.

## One orchestrator, one seam

**Docker swarm, and nothing else.** The by-hand docker replace — find the alias holder, stop it, run
the successor, join it to every other network, poll the gate, roll back on failure — is deleted, and
`dockerhost/` with it. What is left is `swarmhost/SwarmDeploymentDriver`, the only implementation of
`DeploymentDriver`, resolved by ordinary injection.

**`qits.deployments.orchestrator` survives as a GUARD**, not a choice:
`orchestration/DeploymentDrivers` fails the boot unless it says `swarm`. A deployment still carrying
`docker` from before the migration names an orchestrator this build does not have, and failing loudly
naming the key beats deploying the platform with whatever is left. `DeploymentDriversTest` holds it.

**The deployment half of the seam is two verbs**: `apply(ServiceSpec)` makes the described service
exist at the described image, `awaitConverged(name, timeout)` says whether it took. `start`, `stop`,
the container-level `restart`, `connect`,
`disconnect`, `aliasHolders`, `handoff`, `isSelf` and `containerId` went when the second
orchestrator arrived — every one of them is a statement about how *docker* replaces a container, and
keeping them would have made one orchestrator's model look like the contract. **Do not put a
mechanic back on this seam**: `pull` and `networks` are the two verbs here that are not
swarm-shaped, and each is kept for what it ANSWERS (is anything published; what is the membership),
never for how.

**Three verbs beside them are the OPERATOR's, and they are not the mechanics coming back** —
`desiredReplicas`, `scale` and `restart(serviceName)`; see *Scale and restart* below. The test the
removed verbs failed is the one to keep applying: none of these three is a step somebody sequences
to perform a deployment. Each states an outcome a person asked for, and nothing calls two of them in
a row. **A fourth, `removeService(name)`, has NO CALLER LEFT and is kept for what it says.** It was
the plane conversion's, and the conversion went with the plane; what the verb states — "this one
service, named by a row, has been left behind and should not exist" — is exactly the sentence the
plane's own deletion leaves nine times over on the estate, and that is an operator's hand step
because nothing here can name those services (see *Retiring the plane's bare-named services*). It
passes the same test the other three do: by the time it would run the deployment is applied,
converged, recorded and announced. It removes exactly the one named service — the targeted opposite of
`removeEnvironmentContainers`' label sweep — is idempotent, and must never throw.

**So `DeployService.execute` has no branches in it**: resolve → provision → pull (for the
`IMAGE_MISSING` classification) → `apply` → `awaitConverged` → record. What stayed with it is the
bookkeeping: the row per place, the four announcements, the cutover bracket, and the reap **after**
the rows (`Convergence.retired()` comes back as data for exactly that reason).

Three things about the shape, each easy to undo by accident:

- **`nameOf(spec)` is asked before `apply`**, and it is the wire alias: a swarm service's name IS
  its address, so a replace is an update of that same service and both rows of a cutover name it.
  `ServiceSpec.deploymentName` still carries the container-shaped `qits-pd-<env>-<app>-<id8>`,
  because that is what a person greps the host for — it is not the name of anything.
- **`ApplyOutcome.HANDED_OFF` is neither success nor failure.** A deployment that replaces this very
  process leaves its row `STARTING` on purpose; the instance that survives records it, from
  `runningImage`.
- **`FakeDeploymentDriver` is the suite's `@Mock`**, at `DeploymentDriver` itself, and that is where
  it belongs now: there is no choreography above the CLI left to exercise through a booted
  application. What a deployment does to a daemon is `SwarmDeploymentDriverTest`, plain JUnit over
  the package-private `Cli` seam, one argv at a time.

**Under swarm the topology is flat and that is a decision, not a simplification**: every
`--network-add` recreates the task, so a service declares its whole membership at create time —
`qits.deployments.swarm.flat-network` (an *attachable* overlay, which is what keeps CI
step, workspace and agent containers working on it) — and that overlay alone. `qits-platform` was the
second and went with the plane whose services ran on it. The per-application networks the state
machine still computes are dropped by the swarm driver, out loud. A service update keeps the mounts, networks and ports it was created with: changing the shape
of a service is a `service rm` and a redeploy, not a deployment.

### Network aliases: the vhost names docker's DNS cannot make up

**`qits.deployments.extras.<app>.aliases[N]`** is a plain DNS name the application also
answers to on the **shared** network — the flat overlay every service joins. It exists because the
edge proxy carries the platform's vhost names (`registry.dev.localhost` and its siblings) and
docker's embedded DNS **cannot synthesize a `*.localhost`**: a container asking for one gets
NXDOMAIN unless the edge holds the name as a network alias. It is deployment config rather than a
repository's spec for the reason the rest of the family is — where a name resolves is a property of
the platform, not of the code.

Four things about the rendering, each easy to undo by accident:

- **With no aliases the argv is byte-identical to what it always was**: `--network <net>`, the short
  form. One alias or more turns *that one attachment* into the long form, `--network
  name=<net>,alias=<a1>,alias=<a2>`. `aServiceWithNoAliasesGetsTheShortFormItAlwaysGot` pins it —
  the two spellings mean the same thing to swarm, so nothing but the test keeps the diff honest.
- **Only the shared network carries them.** An alias is an address and has to be on the network the
  platform's names resolve on; `qits-platform` and anything else a spec declares keep the short form.
- **Aliases with no shared network to hold them are a REFUSED deployment**, the publish-with-an-ip
  stance: a name asked for and quietly not registered is a peer resolving nothing, hours later and
  somewhere else.
- **`buildUpdateArgv` stays network-free, so an alias change is not a deployment** — the ports
  doctrine exactly. Swarm has no add-an-alias and `--network-add` of a network the service is
  already on is an error, so a live service gains or loses one by hand (`service update --network-rm
  <net> --network-add name=<net>,alias=…`, which recreates the task) or by a `service rm` and a
  redeploy. A declared alias reaches a service only on its next **create**.
- **…which is why a declared alias MISSING from the live service forces that create**, the declared
  volume's arm applied to the second shape change a deployment performs for itself
  (`missingDeclaredAlias`). Without it a declared alias would reach only services created after it
  shipped — measured on the estate: `qits-platform-idp` resolved and `dev-qits-platform-idp` did not,
  after two deployments carrying the declaration. **That measurement was the plane cutover's**, and
  the lever did its job: it made the cutover staggered, each of the nine recreating once on its own
  next deployment to be granted the qualified name, which is what let the plane be deleted a release
  later without a single peer losing an address. **What is declared is deployment config's aliases
  alone now** — the derived half (`PdNetworks.additionalAliases`) went with the plane, because the
  qualified name IS the service's name and an alias equal to a service's own name is a line swarm has
  no use for. Everything the volume arm says holds here word for word — one-directional (an alias
  the service carries that nothing declares is not a reason to recreate, and an application
  declaring none spends no CLI call), an inspect that cannot answer recreates **nothing**, a WARN
  names the service and the alias first, and a self-update never recreates.
  `aLiveServiceWithAliasesAndNoDeclarationsIsUPDATEDANDNOTRECREATED` is the estate protection and is
  named so nobody deletes it. The live aliases are read as the **union over all attachments**, never
  matched against the shared network by name: the daemon resolves a network name to its **id** when
  it stores the spec, so a name match would match nothing anywhere and read as "no aliases at all".

**`update_order` in `.config/qits/deployments.yml`** is `start-first` (default) or `stop-first`, per
repository, and only the repository knows: a published host port, a single-writer store or a held
config volume each make the overlap impossible. This repo says `stop-first`. It reaches the
orchestrator as `--update-order`.

**`publish_mode` is the second such key** — `host` (default, today's behaviour byte for byte) or
`ingress`, per repository. Host mode binds the port from inside the task, so two tasks cannot
overlap and the service also needs `stop-first`; ingress hands the port to swarm's routing mesh,
which keeps holding it while a successor starts, so an ingress service may keep `start-first` and
its lossless rollback. It reaches the orchestrator as `mode=` on the publish, and it means nothing
to an application that publishes no port. **Nothing derives one key from the other**: an ingress
service that says `stop-first` gets `stop-first`. The mode is part of a service's SHAPE, so an
existing service keeps the mode it was created with until a `service rm` and a redeploy — an
update never restates ports. And, like every spec key: **it must ship in the deployer before any
repository writes the line**, since a spec is read at the released tag and an unknown key fails a
deployment.

### The registry credential, and the outcome it used to be mistaken for

**`qits.deployments.registry-auth`** (boolean, `false` shipped) adds
`--with-registry-auth` to the service **create and the update alike**. The flag serialises the
CLI's stored credential into the service SPEC, which is what the swarm agent pulls with; without
it only this component's own warm-up `docker pull` is authenticated — that one runs as this
process, with this process's `DOCKER_CONFIG` — and the task's node-side pull carries nothing. It
is a key rather than always-on because registry reads are anonymous today, so there would be
nothing to serialise; unset, both argvs are what they were byte for byte. **The credential itself
is env wiring**: the container runs as uid 1001 with no HOME, so a deployment points
`DOCKER_CONFIG` at a mounted `config.json`. No code here writes, reads or logs it. It does not
conflict with `--no-resolve-image` — one says do not turn the tag into a digest, the other hands
the agents a credential for a later pull.

**An auth refusal is `PullOutcome.AUTH_REFUSED`, and it is a `FAILED` deployment naming the
credential.** It was `IMAGE_MISSING`, which sent an operator to a pipeline that had published
perfectly well. The two marker lists are asked in **order and the order is the whole point**:
docker's refusal reads `pull access denied for <image>, repository does not exist or may require
'docker login'`, so it carries a missing-image marker inside it and a first-match-wins list would
keep calling a refusal a missing image. `IMAGE_MISSING` keeps its narrowed meaning — the registry
answered and has no such tag (`manifest unknown`, `not found`, `name unknown`). Anything neither
list recognises is still `ERROR`.

**The startup sweep settles an in-flight row from what is RUNNING** (`DeploymentDriver.runningImage`,
one read per `STARTING` row that named something): the image carrying the row's sha is `ACTIVE` with
the prior actives of that place decommissioned, another sha is `SUPERSEDED` (swarm's `UpdateStatus`
supplies the wording), and no such service is the interrupted-by-a-restart `FAILED` every other
in-flight row takes. The **image** is the check and `UpdateStatus` is only wording: that
field holds the most recent update, so a later deployment overwrites the verdict of the one a row is
about. It replaced "is this row's name me", which cannot tell a completed succession from a
rolled-back one — under swarm the service keeps its name across both.

### `UpdateStatus` does not say WHICH update, and `awaitConverged` had to (2026-08-13)

The sweep's caution above is the whole story, and reading the field *during* a cutover was the same
mistake in a worse place. `service update --detach` returns before the daemon has replaced
`UpdateStatus`, so the first poll after an update reads either the **previous** cutover's terminal
state or, in the window where swarm has cleared it, **nothing at all**. Both were read as an answer.
Measured on qits-docs: the deployer logged "Deployed" 43ms after issuing the update, off a
`completed` an earlier deployment had left, wrote the row `ACTIVE` with an empty detail and
decommissioned the predecessor that was serving — while swarm spent the next 25 seconds rolling the
successor back (`rollback_completed`, and the row never said so).

So the driver records, per service name, **when it issued the update**, and matches
`.UpdateStatus.StartedAt` against it. Four things hold it up:

- **`apply` and `awaitConverged` land on one `@ApplicationScoped` bean**, which is the carry the
  retired docker driver used for its in-flight cutover state. The map is written only on the update
  arm (a create has no `UpdateStatus` to confuse and *clears* the key), pruned on write, and
  consumed by the verdict in a `finally` — an issue instant that outlived its answer would make the
  next question about that service wait for an update nobody issued.
- **A status older than the issue instant, an empty status and an unreadable stamp are all
  PENDING** — never a verdict, never a crash. In particular the empty arm no longer falls through
  to the task check: under `start-first` the *predecessor's* task is still `Running`, so that
  fallback answered "converged" about the deployment being replaced. It stays what it always was
  for a **fresh create**, where there is no predecessor to mistake.
- **The tolerance is 5s and the asymmetry is the argument.** In real time the daemon stamps
  `StartedAt` after the CLI returned, so only clock skew can make this deployment's own status look
  early — milliseconds on one host, a second or two for a remote daemon. What it must reject is the
  *previous cutover*, which is minutes to months away. Nothing plausible sits between.
- **The deadline still bounds everything.** An update whose status never appears fails at the health
  timeout with a detail naming what was last seen, rather than passing.

**The timestamp has two spellings and only one of them is RFC3339** — measured on docker 29.7.2:
`service inspect --format` prints Go's own `time.Time.String()`
(`2026-08-13 10:21:12.655795838 +0000 UTC`), while the JSON body of the same inspect says
`2026-08-13T10:21:12.655795838Z`. `parseStartedAt` takes both and answers null to everything else
(`<nil>`, `<no value>`), which is what makes an unreadable stamp patience instead of an exception.
`UPDATE_STATUS_FORMAT` is `State|StartedAt|Message` in that order for one reason: the message is
free text from the daemon and is read as the remainder of the line, so a field behind it would be
whatever the message left over. `RUNNING_IMAGE_FORMAT` embeds it and its parser skips the stamp.

## FAILED was five outcomes, and now it is one

`PdDeploymentStatus` is the single source of the words — an entity enum, a `varchar(32)` with **no
check constraint** (V1 says why), so the vocabulary grows without a migration and every historical
row keeps the word it was written with. **Nothing is backfilled and nothing is relabelled.**

Three of the five outcomes `FAILED` used to cover have their own word, one writer each:

| status | meaning | written by |
| --- | --- | --- |
| `ROLLED_BACK` | the successor never converged and the orchestrator put the predecessor back — it is serving | `DeployService.execute`, off `ConvergenceOutcome.ROLLED_BACK` |
| `SUPERSEDED` | a restart interrupted this in-flight row and the place is already being served | the startup sweep's verdict, and `sweepHeldSpecReads()` for a `SPEC_UNREADABLE` row whose place a later deployment took |
| `GONE` | a formerly `ACTIVE` row whose container two observation passes found absent | `DeploymentObserver.demote` |
| `SCALED_TO_ZERO` | the workload is **deliberately stopped** — somebody scaled the application to 0 | `ApplicationScaling` on the operator's own action, and `DeploymentObserver.pause` for a scale performed by hand |
| `DECLARATION_REFUSED` | the release's `.config/qits/configuration.yml` was not seeded into qits-configuration, so the deployment was **never scheduled** | `DeployService.deployReadSpec`, off `DeclarationRefused` — see the declaration section below |
| `FAILED` | the attempt ended and **nothing is known to serve the place** | everything else — refused apply, image pull error, convergence failure with no rollback, interrupted row with no successor |

Four things that are decisions rather than details:

- **The driver already knew.** `awaitConverged` has returned a distinct `ROLLED_BACK` verdict since
  the swarm migration; `execute` flattened it to `FAILED` on `!converged.converged()`. The refinement
  is reading the answer that was already there.
- **The four events stay four.** A `ROLLED_BACK` outcome still announces `DeploymentFailed` —
  consumers care that it did not go live — and rides the record's existing `status` **string**, which
  is exactly what a string on the wire was for. No vocabulary-jar change, no `EventWireReflection`
  entry. `SUPERSEDED` and `GONE` announce nothing, because the sweep and the observer announce
  nothing.
- **`GONE` recovers.** The observer's `FAILED` → `ACTIVE` arm heals `GONE` too: a demotion that
  self-heals must heal whatever word the demotion wrote, or the observation could write a status it
  could never take back.
- **The rollback pins are untouched, and that is verified rather than assumed.** `RollbackPins.SERVED`
  is `{ACTIVE, DECOMMISSIONED}` and the serving scan keys on `ACTIVE` — a demoted row was excluded as
  `FAILED` before the word existed and is excluded as `GONE` now. Same for `listActiveByApplication`
  and the sweep's `listByStatus(QUEUED|STARTING)`: every status query here is a **positive** list, so
  a new word leaks into none of them. Keep it that way — a `status != ACTIVE` filter would be the
  regression.

  **`DECLARATION_REFUSED` was audited against that claim rather than assumed into it**, and the
  audit is the shape to repeat for the next word: `RequestLifecycle.IN_FLIGHT`, the observer's
  candidate and recoverable sets, `RollbackPins.SERVED`, the sweep's two `listByStatus` queries and
  the retirement door's two refusal sets are all positive lists, so the new word is completed,
  unobserved, unpinned and retirable without a line changing. The one negative test on a write path
  is `ApplicationScaling`'s `!= QUEUED && != STARTING`, and it is unreachable here for a structural
  reason rather than a lucky one: the scale door refuses an application whose current row names no
  service, and a deployment refused before it was scheduled never named one.

## The health gate is patient, and that is not a tuning choice

`HealthGate` (in `deployments/control`, polled by the driver) ends early on exactly two verdicts:
**healthy**, and a container docker cannot inspect at all. **Restarting is PENDING. Running-but-
unhealthy is PENDING.** The deadline — `qits.deployments.health-timeout-seconds`, unchanged
— is what fails a deployment, and the verdict then reads `container still <state> after <n>s` with
the log tail under it.

The reason is structural rather than generous. `docker run` takes **one** network, so a fresh
container starts on its primary one and every other join happens after the start
(`DeployService.join`). A PostgreSQL-backed application runs Flyway immediately, cannot resolve the
postgres wire alias yet, and dies with an acquisition timeout; `--restart unless-stopped` brings it
back seconds later into a world where the joins are done, and the second boot works. The old gate
read `restarting/unhealthy` once and failed the deployment 18 seconds in — measured on
qits-platform-idp's first PostgreSQL deployment. H2-era applications could never hit it, which is
why the instant fail survived this long.

**The race itself left with the docker path**: a swarm service declares its whole membership when
it is created, so a first boot never runs before its peers are addressable. What survives is the
patience, and it survives where it still applies — `HealthGate.healthy` is the one reading
`DeploymentObserver` settles a row on, so restarting and running-but-unhealthy are not a dead
deployment there either.

`HealthGate.await`, the polling loop itself, **has no caller left** and says so in its own javadoc.
It was the docker cutover's; swarm reaches its own verdict through `UpdateStatus`.

## The vocabulary rename, and what is left of it

`singleton` → `platform` was a rename of a word that has since been deleted outright. `PdDeploymentTarget`,
the label `qits.platform.deployments.target`, the network `qits-platform` and the `platform:<name>`
key stand-in are all gone — see *The platform plane is deleted* below.

**What survives is a tolerance in the parser, and it is permanent.** `deployment_target` is accepted,
unvalidated and acted on by nobody: `platform`, `environment`, `singleton` and any word whatsoever are
read and dropped. Not because any of them means something, but because **a spec is fetched at the
BUILT sha** — a rollback pin, a redeploy of an older commit and every repository that still carries
the line all present a file containing the key, and this parser fails a deployment on an unknown one.
Making it unknown would turn every such deployment red permanently rather than for the length of a
sweep. Do not write it into a new file, and **do not remove the tolerance**.

**The config namespace is `qits.platform.deployments.*`** — `platform` qualifies `deployments`, it
is not half of one word. It was `qits.cd.*` in the ancestor and `qits.pd.*` for one release here; a
deployment carrying an old spelling configures nothing and fails loudly at boot (SmallRye rejects an
unsatisfied `@ConfigProperty`), which is the intended failure. The env form is
`QITS_PLATFORM_DEPLOYMENTS_*` — every wrapper and compose file that injects config moves with it.
The one family read in the DOTTED spelling only is `qits.deployments.extras.<app>.*`, and
`ServiceExtras` says why: an underscore cannot tell `qits-ci`'s keys from `qits-ci-daemon`'s.

## Adopting what qits-cd left behind

The labels are `qits.platform.deployments.*`. Two earlier spellings exist on the host — `qits.cd.*`
from the retired ancestor and `qits.pd.*` from this component before the namespace was written out
in full. **Nothing here reads either, and nothing should start to.** A holder with no
`qits.platform.deployments.environment` label is unclaimed — a compose original, a bootstrap seed, a
qits-cd container, a container this component started before the rename — and unclaimed means
*adoptable predecessor*. Reading a legacy label would make those containers look like another tier's
and leave them running beside their replacements, which is the one failure the cutover exists to
prevent.

The container **name** prefix is `qits-pd-` (the ancestor's was `qits-cd-`), and it **stays short
through the namespace rename**: docker's name charset has no dot, and
`qits-platform-deployments-<env>-<app>-<id8>` spends 26 characters before the two words a person
actually reads. So it is the namespace's abbreviation, spelled once in `ContainerNames`. It is how a
person reads the host and what a bootstrap greps; it is never how a predecessor is found (that is
the alias). Wrapper and component changes land together.

## Names, and the one that is an address

Two derived shapes, and only one of them resolves:

| | every service |
| --- | --- |
| container name (`ContainerNames`) | `qits-pd-<env>-<app>-<id8>` |
| wire alias (`PdNetworks.alias`) | `<env>-<app>` |

**There was a second column and it was the platform plane's** — `qits-pd-<app>-<id8>` and the bare
`<app>`, both of them unqualified because the plane served every tier and a consumer had to reach it
without knowing which one it ran in. Both took a `PdDeploymentTarget` to say which. The plane is
deleted, so each shape is one derivation with no branch in it, and every name carries the tier.

**The wire alias is the address, and it is derived in one place because everything that has to
agree about an address takes it from here** — swarm's service name first of all, which is also what
makes a replace an update rather than a second service.

The environment qualifier exists because the flat overlay is shared by every tier: without it two
tiers' copies of one application hold the same address there.

**Deleting the second column RENAMED nine live services, and that is the one cutover in the whole
change.** A swarm service's NAME is its wire alias and swarm cannot rename one, so the first
deployment of each of the nine under this code creates `<env>-<app>` beside the bare-named service
that was serving. What made it survivable was staged a release earlier: every one of the nine already
answers on both names, because the qualified form was declared as an extra network alias and each
service was recreated once to be granted it. The bare-named predecessor is **not** retired by
anything here — see the hand step below.

## Networks are docker's bookkeeping, never a row

Hub and spoke, as README describes. Two things to leave alone unless you mean it:

- **`aliasHolders` searches the union** of every network the fresh container will be on, legacy one
  included. Narrow it and a deploy starts a second copy beside a container that holds its alias on
  `qits-net` alone — which is every container on the platform until it has been redeployed once.
- **…and the union is then filtered by the holder's `qits.platform.deployments.environment`**, the
  other half of the same thought. The legacy network is shared by every tier, so the union also returns another tier's
  healthy copy of the same application under the same alias; stopping that would be one tier reaching
  into another. This tier's label → predecessor; another tier's → left alone; **unlabelled** →
  adoptable. (Both bullets are the **retired docker driver's**, kept because the reasoning is what
  `legacy-network` still rests on. Under swarm there is no predecessor search at all: a service is
  found by NAME, the name is the wire alias, and a platform service's alias is bare — which is
  exactly what makes the existing fleet's platform services be UPDATED in place by the first
  deployment under the new code rather than duplicated beside it. No label filtering is involved,
  and the pre-change containers need no adoption arm because they were never a different service.)
- **A join asked for and not granted FAILS the deployment.** `docker network connect` reports
  "already there" as an error, so the driver tells that wording apart from a refusal and only the
  refusal counts. It has to fail rather than warn: the health gate curls localhost *inside* the
  container, so it passes perfectly well on a network nobody else is on, and the cutover would then
  remove the predecessor under an unreachable successor. The *reconciliation's* joins stay
  best-effort — those are a self-heal, not this deployment's own reachability.
- **`qits.deployments.legacy-network`** (default `qits-net`, `Optional<String>` because
  SmallRye reads an empty value as absent) is the transition membership. **Emptying it is the enforcement flip**, a
  later phase that needs every direct cross-application URL migrated first. `LegacyNetworkOffTest`
  already runs that posture. An environment teardown never disconnects anything from it and never
  removes it, even when it IS that environment's bundle — which is exactly the dev tier's shape.

## Untrusted input

Two validators, split by the module boundary rather than by taxonomy:

- **`PdIdentifiers`** (`environments`) — names, branches, health paths, resource names, database
  names: what the topology **stores**, checked where it is stored. Names become docker network
  names, aliases and image path segments, so the charset is the dns-label one.
- **`DeploymentIdentifiers`** (`deployments`) — shas, repository ids, repository names and their
  project ids, run ids, resource-attribute values: what only ever reaches an argv or the spec
  read's URL, checked beside it.

**The health path is the strictest and stays that way.** It is the one value interpolated into a
string a *shell inside the container* runs (`--health-cmd`), so it gets an allowlist, no exceptions,
and is re-checked at the last line before the argv (`SwarmDeploymentDriver.healthFlags`). Three
callers use the same check: the API, the spec parser (repository-authored input) and the argv.

**`health_cmd` is the one value with no charset, and the exception proves the rule.** It is not
interpolated into a shell string — it *is* the string, chosen by a repository for its own
container, so an allowlist would refuse the probes worth writing (`pg_isready -U postgres || exit
1`) while granting nothing: the image's entrypoint is already that repository's, and the command is
one argv element to `ProcessBuilder`, never re-split. `DeploymentIdentifiers.requireHealthCmd`
bounds it to one non-blank line of 512 characters, at the parser and again at the argv. It
**replaces** `health_path` (the parser fails a file setting both), so the path is neither used nor
checked when a command is present. It is not stored: the spec is read before every deployment, and
the one path that resolves targets from the catalogue instead records failures and deploys nothing.

**`resources:` takes the health-path treatment, and it needs one checkpoint more than the health
path does.** Both halves of an entry are repository-authored. The **resource name** becomes an
environment-variable key on a `docker run` (`QITS_RESOURCE_<NAME>_URL`), which is the health path's
situation exactly. The **database name** additionally lands in DDL run against a postgres instance
**the whole platform shares** — and DDL has no bind variables, so the allowlist is not a belt there,
it is the only guard. Hence `requireResourceName` (`[a-z][a-z0-9-]{0,31}`) and
`requireDatabaseName` (`qits_[a-z0-9_]{1,58}`), and three checkpoints rather than two: the parser,
the line before the SQL string is assembled, and the argv. The mandatory `qits_` prefix is the
structural half of the guard — it excludes `postgres`, `template0/1` and every `pg_*` name by
construction, so the namespace a repository can reach is disjoint from the instance's own.

**What a repository can NAME versus what this component INJECTS.** A repository names a database of
its own; every VALUE that reaches the container for it — the url, the role, the password — is
derived or generated here. So the rule that nothing arriving over HTTP contributes a credential to a
`docker run` is unchanged by provisioning: the credential is this component's, and the registry row
is its only copy.

**Provisioning speaks SQL, not shell.** `CREATE ROLE` / `CREATE DATABASE` / `REVOKE` / `ALTER …
OWNER` over plain JDBC — no `psql`, no `docker exec`, no process. `exec` is still not in the docker
vocabulary and must not enter it. The postgres superuser password comes from
`qits.deployments.postgres.admin-password` (deployment config, the domain that already
holds the socket), has no default, is never stored in a row and never reaches an argv. **There is no
`DROP` and none is coming**: marking a resource obsolete is future work, and it will be a mark.

**The `platformdeployments` database is credential-bearing.** `pd_resource.password` is the single
authority for every provisioned application's credential — a postgres password or an idp client
secret, one column doing both jobs because the row's `resource_type` already says which. Treat it
with the sensitivity of the `qits-deployments-config` volume. No statement containing a password is
logged, no failure detail names one, and `PgResourceProvisioner.literal` refuses a password it could
not quote safely rather than escaping it cleverly; `HttpIdpClientProvisioner.validated` holds the
idp side of the same rule — a returned secret is checked, never echoed, and refused rather than
escaped if it does not look like something this component can use.

Argvs are assembled for `ProcessBuilder`, which never re-splits — but do not lean on that:
validation stays at the boundary and the belt stays at the argv.

Mounts, published ports, groups, network aliases and extra env in a *started* container's argv come
from the **deployment's own config and nowhere else** (`qits.deployments.extras.<application>.*`,
read by `ServiceExtras`). **Nothing PUSHED over HTTP may contribute to a `docker run`**; this
component's own API is deliberately open on the platform's networks, so nothing arriving on it may
shape an argv. `ServiceExtrasTest.anotherApplicationsKeysAreNeverRead`, plus
`extrasOfAnotherApplicationDoNotLeakIn` on **both** driver tests, asserts the absence as the
security property. A `docker exec` is the regression, and so is anything the intake can reach.

**The word is PUSHED, and it narrowed on purpose when qits-configuration landed.** The extras may
now be PULLED — this process, with its own machine identity, reading a named service it was
configured to trust (see the extras-url section below). That changes the source and not the guard:
nothing pushes config into a deployment, no caller of this component's API can name a key, and the
url is deployment config like everything else. The consequence to carry is that
**qits-configuration is credential-bearing infrastructure** — treat its database and its write
surface with the sensitivity of the `qits-deployments-config` volume.

**The family is typed, and an unknown or malformed key is a refused deployment** — never a warning
and a dropped flag, which is a container that boots, passes its gate and has lost its volume.

**And it is read PER ARGV, not out of the boot config** (`ExtrasSnapshot`, `deployments/control`).
Quarkus' config-dir source reads `<user.dir>/config/application.properties` once, at boot, so an
edit on the deployment host's config volume was inert until the process was replaced — and every
deployment re-stamped the boot snapshot onto the service it updated, which reverted a live
`service update --env-add` fix on 2026-08-16 and cost a day. Both argv builders take **one**
snapshot — that file layered over the boot config, at a higher ordinal — and hand it to every
reading, because `ServiceExtras` rests on "every reading agrees" and that is only true of a fixed
`Config`. `qits.deployments.extras-file` names the path. **Absent is the boot config
itself**, byte for byte what a dev run and the clone-alone suite always had; **present and
unreadable is a REFUSED deployment naming the path**, because a fall-back to boot values is the
stale value the whole thing exists to kill and would ship a green deployment carrying it.

### The file is the cold-boot source, and qits-configuration is the source

**`qits.deployments.extras-url` is optional and UNSET SHIPPED**, and unset is the file
behaviour above byte for byte: no request, no parse, nothing to configure — and, since the
declaration seed, nothing seeded either: unset means there is no store at all. Set — a deployment
sends `QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL=http://qits-configuration:8080` — **that service is
AUTHORITATIVE**, read once per argv build at
`GET <url>/configuration/api/applications/<app>/envs/<env>/resolved?version=<version>`, whose
`properties` map arrives in the full `qits.deployments.extras.<app>.*` spelling.
`ServiceExtras` stays the single parser of the grammar — nothing in `confighost` translates a key.

**The address grew a tier and a version, and the example lost its tier prefix in the same change.**
It used to be `.../applications/<app>/resolved` against `http://dev-qits-configuration:8080` — a
read addressed by the application alone, which could only tell dev's configuration from prod's by
being a different service per tier. The tier is a path segment now and the version is a query
parameter, so **one qits-configuration answers for the whole platform**: the segment is what makes
two tiers two documents, and the parameter is what makes deploying an older version give back that
version's configuration rather than the newest one's. Same key, same `Optional` semantics, same
body — `{headRevision, properties}` is unchanged, and so is everything above the seam.

### …and the version is only asked for when the release SEEDED one (2026-09-07)

**The parameter went out unconditionally for exactly one release, and it wedged every deployment on
the platform.** qits-configuration answers **404 for a version it holds no declaration for**, which
is right and is documented on its own route: absent means "I am not asking about declarations",
present means "resolve me against this document", and a bare entry map would be a configuration
missing every default the caller asked for with nothing to say so. But the deployer seeds a
declaration **only** for a release whose tag carries `SpecSource.DECLARATION_PATH`, and almost no
repository in the fleet carries that file yet. So from 2026.907.183748 every release of an
undeclared repository ended `…/resolved?version=<v> is this deployment's extras and could not be
read after 2 attempts: it answered 404`, and the row was then "recovered by observation" against the
**old container that was still running** — a deploy that silently did nothing, platform-wide.

**The fix is that the release says whether the version may be asked about at all.** Seeded, the read
is version-addressed exactly as before; not seeded, it is the version-LESS form
(`…/envs/<env>/resolved`, no query) that qits-configuration's own javadoc calls "EXACTLY today's
behaviour and never a 404" — the entries alone, which is what a repository that declares nothing has
to configure with anyway.

- **It is a value threaded from where it is known, not a retry on the 404.** `DeployService.
  deployReadSpec` asks `declaration.present()` once, seeds on it, and carries the same boolean into
  every `execute` → `Plan` → `DeploymentDriver.ServiceSpec.declarationSeeded()` →
  `DeploymentExtrasSource.forDeployment(…, declarationSeeded)`. A 404-retry was the alternative and
  is the wrong shape: it cannot tell "this repository declared nothing" from "the store lost a
  document it accepted a moment ago", and the second is a **real error** that must keep refusing the
  deployment. A retry would paper over exactly the one worth failing on.
- **It is the honest answer on the ROLLBACK path for free.** A redeploy of an older version reads
  that tag's spec and — where the spec is there — that tag's declaration, on the manual door as on
  the release door. A tag cut before the file existed simply answers `present() == false`, so it
  takes the version-less arm without anything special being written for it.
- **One WARN per version-less read, and it is the cutover's inventory.** It names the application
  and the version and says the read was version-less because the release declared nothing. Every
  line is one repository that still has to grow `.config/qits/configuration.yml`; the day the log
  falls silent is the day the parameter can stop being conditional. It is built as a string rather
  than deferred to `warnf`, for the reason the `config-revision=` line is: it is a sentence people
  and suites grep for.
- **This repository declares itself, and that is what made the fix release deployable.** See below.

**AUTHORITATIVE MEANS SOLE, and that is the 2026-08-17 correction.** With the url set the file is
**not read at all**: the served map over this process's boot config is the whole snapshot. It was
layered *above* the file for one release, on the reasoning that a half-migrated platform should keep
deploying applications whose keys had not moved yet — which read well and deployed badly. A key
**deleted** from the service came straight back out of a file nobody had emptied (measured: a
revision serving 2 properties, the file's stale third still winning at ordinal 1000), so the one
operation the service exists to make possible was the one it could not perform. Two sources cannot
both be authoritative, and the one left on a volume is the stale one by construction.

**The rollback is the key itself**: empty `extras-url` and the file is the source again — but it may
be months out of date by then, so re-render or export it before leaning on it.

Five things about it, each easy to undo by accident:

- **The seam is `DeploymentExtrasSource`, in `deployments/`, and the HTTP is
  `confighost/ConfigHostExtrasSource`, in `service/`.** The driver injects the seam and no longer
  knows what a config file is. **One call is one snapshot**, which is the invariant `ServiceExtras`
  rests on; calling it twice for one argv is the bug the shape exists to prevent, and both argv
  builders call it exactly once.
- **An unreachable or non-200 service REFUSES the deployment**, naming the url, and there is
  **deliberately no fall-back** — not to the file, not to the boot config, not to anything read
  earlier. A stale extras value is the failure that cost 2026-08-16 and it ships invisibly, as a
  green deployment. The refusal is a `ServiceExtras.Refused`, so it reaches the row through the
  driver's existing refusal arm with nothing created.
- **A 404 is NOT an answer here**, and that is the one place this differs from the spec read. A
  repository with no `deployments.yml` really does deploy with the defaults; an application
  qits-configuration has never heard of is an application whose extras this deployment cannot know
  it is missing.
- **The patience is two keys and a bounded budget** — `extras-timeout-seconds` (5) and
  `extras-attempts` (2, spent a second apart). A service being redeployed is a few seconds of
  refusals and no deployment should die of one; an outage that outlasts the budget must be a loud
  refusal rather than an unbounded wait on `pd-deploy-worker`, which is single-threaded with every
  other event queued behind it. **A body that will not parse is never retried** — it will not parse
  a second time either.
- **The credential is the named oidc client and its switch is the extension's own.**
  `quarkus.oidc-client.configuration.client-enabled` is false shipped, and off the read carries the
  `X-Qits-User`/`X-Qits-Roles` pair alone — the posture of a platform running qits-configuration
  behind forward-auth on qits-net during the migration. There is no key of ours beside it, so two
  spellings cannot disagree; the deployment-side family is `QUARKUS_OIDC_CLIENT_CONFIGURATION_*`,
  the sibling shape qits-workspaces uses for its git-host client. **The default (unnamed) client is
  disabled in `application.properties` and must stay disabled**: the extension creates it whether or
  not anything injects it, and an enabled one with no `auth-server-url` fails the boot naming a key
  nobody meant to set.

**What is NOT recorded, and it is an open debt rather than an omission.** The read logs
`config-revision=<headRevision>` at INFO beside the url, and that is the only place the revision a
deployment was configured with is written down. It does not reach the deployment row's `detail`:
that is written by `DeployService` out of the driver's verdict, and the extras are read a layer
below the driver, so a revision could only ride there by widening `ApplyResult` and the seam's
return type together. Worth doing the day the row's detail is asked to carry more than the
orchestrator's own words.

This component's own env flags (`QITS_ENVIRONMENT`, `QITS_APPLICATION`, `QITS_DOMAIN`,
`OTEL_RESOURCE_ATTRIBUTES` and its `QUARKUS_`-spelled twin) are written **before** the deployment's
own, and docker keeps the **last** assignment of a repeated key — measured, not assumed. So they are
defaults an operator overrides, and the ordering is the precedence rule: never reorder them past the
extras.

### `QITS_DOMAIN`: the platform's domain is ONE fact (2026-09-26, qits-387)

**The bootstrap used to fan the domain out into five per-service composed spellings** —
`QITS_EDGE_ACME_DOMAIN`, `QITS_IDP_BROWSER_SSO_COOKIE_DOMAIN`, two `*_BROWSER_HOSTS`,
`QITS_IDP_WEBAUTHN_ORIGINS` and `QITS_IDP_BROWSER_SSO_CANONICAL_ORIGIN` — each able to go stale on
its own. The edge's copy and the idp's **did** diverge, and sign-in on the live platform broke. So
the domain is stated once, as configuration, and propagated by the deployer to every container
exactly as `QITS_ENVIRONMENT` is; a service derives its own names from it and **no service carries a
hostname in its configuration**.

- **`qits.deployments.platform-domain`, read from the bare `QITS_DOMAIN`** — not a
  `QITS_PLATFORM_DEPLOYMENTS_*` spelling, because it is not this component's setting: it is the
  platform's one domain, which this component happens to be the thing that hands out. The deployer
  receives it under the same name it writes.
- **It has no default, and that is the point.** No hostname this repository could ship would be
  right for more than one installation, and a baked-in literal would be the sixth stale copy of the
  fact the change exists to state once. The empty default keeps the no-default rule intact the way
  `postgres.admin-password` and `extras-url` do: SmallRye reads empty as absent, the field is
  `Optional<String>`, and blank is filtered at the argv.
- **Blank or absent writes NO VARIABLE.** `QITS_DOMAIN=` is worse than the variable's absence — a
  consumer reading an empty string has been told an empty hostname and composes nonsense out of it,
  where a consumer finding nothing falls back to the default it ships. `QITS_ENVIRONMENT`'s null
  guard, one word further on.
- **`DOMAIN_VARIABLE` is in `DEPLOYER_OWN_VARIABLES`**, so the update diff cannot `--env-rm` it.
  Config states it nowhere, which is exactly the condition that set exists for; that it is written
  conditionally changes nothing, because membership protects a key from removal rather than
  asserting it is present.

### The declaration goes the OTHER way, and it goes before the deployment (2026-09-07)

**Everything above is the read. This is the write, and it is the only one this component makes to a
peer.** What qits-configuration RESOLVES is an application's own declaration with the platform's
overrides layered over it — and that declaration is a file in the repository,
`.config/qits/configuration.yml`, beside the deployment spec. So a release seeds it: the file is
fetched at the released tag (above) and POSTed to the store before the deployment it belongs to is
scheduled.

    POST <extras-url>/configuration/api/applications/<app>/declarations/<version>?deploymentTarget=<platform|environment>
    Content-Type: application/yaml
    <the file, verbatim>

Seven things about it, each easy to undo by accident:

- **The ORDER is the feature.** The seed sits in `DeployService.deployReadSpec` between `queue` and
  the execute loop: after the rows exist, so a refusal has somewhere to be recorded and is readable
  on the tier's listing where every other outcome is; and before anything is scheduled, so the
  container that comes up resolves against **its own version's** declaration. Seeded after the argv,
  a deployment would go green against the previous version's configuration — invisibly, which is
  the failure class this whole line of work exists to end. It is still pre-scheduling: `queue`
  writes rows, `execute` is what asks an orchestrator for anything.
- **ONE seed per deployment EVENT, not per row.** The POST is addressed by (application, version)
  and is idempotent by content hash, so a fan-out shares it — a declaration is a statement about a
  released version and says nothing about where it lands. That is why the call is outside the loop
  that queues rows, and it stays one when a promotion ladder makes places plural. Today
  `entryTiers()` answers with one tier, so the difference is structural rather than observable.
- **A refusal is `DECLARATION_REFUSED` and it is TERMINAL**, unlike `SPEC_UNREADABLE`. Both of its
  flavours were *answered*: the store read the file and refused it (409/422 — the released tag
  carries a broken file, and reading it again says the same), or the store outlasted the patience
  budget and the deployment had to be decided. Nothing re-asks either. The detail's **first line**
  is what distinguishes them, because they ask different things of a person — one names the
  repository and the remedy, the other names the url that would not answer.
- **The status map is asked in two halves.** 2xx is done (201 created, 200 the idempotent no-op).
  409 and 422 are `DECLARATION_BROKEN` **immediately and never retried**. 5xx, a timeout and a
  refused connection are `SERVICE_UNAVAILABLE` after the budget. **Any other 4xx is
  `SERVICE_UNAVAILABLE` with no retry** — a 404 on a path this component built is a mis-deployed
  store, not patience material, and it is deliberately not `DECLARATION_BROKEN` because nothing read
  the file: sending a person to a repository over a 401 would be the wrong repository.
- **It shares the extras read's WHOLE posture and adds no key of its own** — the same
  `extras-url` base, the same `X-Qits-User`/`X-Qits-Roles` pair, the same `ExtrasBearer`, the same
  `extras-timeout-seconds`/`extras-attempts` budget. That is not economy: it is the same peer, and
  **the peer count stays one**. A second url key would let a platform seed one store and resolve
  against another, which is a deployment configured by a document nobody wrote; a second credential
  would be a second peer and would want the argument the first one made.
- **Unset `extras-url` is a NO-OP, and it has to be.** Unset means the extras are the config
  volume's file — there is no store — so a refusal there would be every file-mode platform
  failing every deployment for the absence of something it was never configured to have. It logs
  at debug and the deployment proceeds.
- **The adoption sweep neither fetches nor seeds**, and `legacySnapshot` says so in its own javadoc.
  An adopted row's declaration was seeded before its own deployment was queued, by the instance that
  ran it; the sweep runs at boot, seconds after a cutover, and its whole design is to reach no peer
  it does not have to. Its one spec read is already the exception and is bounded for that reason.

#### …and this repository DECLARES ITSELF (2026-09-07)

`.config/qits/configuration.yml` is committed here, beside the deployment spec, and it is the first
one on the platform. It exists for a reason that outlives its content: **the release that shipped
the version-less fallback had to be deployable by the deployer that was still running without it.**
With the file present the seed runs, qits-configuration holds a declaration for that version, and
the still-broken version-addressed read finds one — so the fix deploys itself past the defect it
fixes. A repository being able to unwedge its own deployment that way is a property worth
remembering the next time this component ships a change to the read.

Four things about the document, each easy to undo by accident:

- **A malformed file is `DECLARATION_REFUSED` and the release is undeployable.** The store parses it
  with the estate's one strict parser and refuses an unknown top-level key, an unknown attribute, an
  unknown type and a duplicate key name. Nothing here parses it — `GitHostSpecSource` returns it
  unparsed on purpose — so **an edit is verified against qits-configuration's own
  `DeclarationParser`, by running it**, not by reading this file's neighbours.
- **Every key declares a name and a type and NO default.** That is a complete statement — the key
  exists, and until somebody sets it the resolved read does not carry it — and a default written
  blind would be layered under the stored entries, invisible on this platform and wrong on a fresh
  install. `deployments/…/OwnDeclarationTest` holds only what a hand edit can silently break: the
  file being where the deployer fetches it, the closed top level, and no key declared twice. It is
  deliberately **not** a parser, for the reason the declaration read is unparsed.
- **No `serviceAddress`, deliberately.** `QITS_EVENTS_URL` and `QITS_OBSERVABILITY_URL` are the
  obvious candidates and are `string` here: a serviceAddress is RENDERED by the platform and refuses
  a stored value, so declaring one turns every hand-set address on the live platform into an ignored
  row on the deployment that adopts it. That flip is the config-declarations epic's, one application
  at a time, with the entries retired in the same step.
- **`QITS_RESOURCE_*` is not declared and must not be.** Those come from the deployer's own resource
  registry, the single authority for a provisioned credential, and configuration is deliberately
  unable to state or delete one. They are extras today only because the bootstrap seeds them for the
  cold boot.

### An update states removals now, and a hand `--env-add` no longer survives one

`buildUpdateArgv` only ever `--env-add`ed, so a variable **removed** from an application's extras
stayed on the live service until somebody removed the service — measured on 2026-08-17, on the
deployment that proved the flip: the entry was deleted, the deploy went green, the stale env kept
serving. So the update reads the live service's own environment
(`docker service inspect --format {{range .Spec.TaskTemplate.ContainerSpec.Env}}…`, the inspect
pattern the rest of the driver already uses) and emits `--env-rm` for every key on it that nothing
in this argv states.

**Read the consequence as the feature, because it is the campaign's whole point:** an operator's
`docker service update --env-add` on a live service is now REVERTED by the next deployment of that
application. State moves to qits-configuration; a fix applied to a container is a fix that lives
until somebody deploys. That is the trade the demotion buys — a config value has one home instead of
two, and the one that wins is the one that is written down.

**What is never removed is a family, not a list of exceptions**, and it has three members:

- **everything this argv states** — the extras' own `env`, which is the whole of what config says;
- **`DEPLOYER_OWN_VARIABLES`** — `QITS_ENVIRONMENT`, `QITS_APPLICATION`, `OTEL_RESOURCE_ATTRIBUTES`
  and its `QUARKUS_` twin, this component's identity set. It writes them on every argv and config
  states none of them, so a diff against config alone would remove and re-add all four every time;
- **anything under `QITS_RESOURCE_`** — `ResourceProvisioning` injects those from the registry row,
  which is the single authority for the credential. Config cannot state a provisioned password and
  must not be able to delete one.

Four more things, each easy to undo by accident:

- **Only an UPDATE diffs.** A create has no predecessor and `service create` has no such flag.
- **An inspect that cannot answer removes NOTHING**, with a WARN. Losing an application's whole
  environment because one CLI call failed is a worse failure than carrying a stale key one more
  deployment, and the next deployment asks again.
- **A line with no `=` is skipped**: an env VALUE may contain a newline, so a wrapped line is a
  continuation rather than a key — and `--env-rm` of a name that is not there is a refused
  deployment, not a no-op.
- **The removals are sorted**, so one deployment's argv is the same argv twice and two run logs
  diff cleanly.

**The deployer's own self-update is the regression to watch, and it is pinned by a test.** Its
extras carry the flip — `QITS_PLATFORM_DEPLOYMENTS_EXTRAS_URL` and the
`QUARKUS_OIDC_CLIENT_CONFIGURATION_*` credential the read presents — so they survive because they
ARE extras. A deployer that env-rm'd its own extras-url mid-self-deploy would come back reading the
file it was demoted from, silently, on a green deployment.
`theDeployersOwnSelfUpdateKeepsTheKeysThatPointItAtQitsConfiguration` holds it. **Anything the seed
stack sets on the deployer and the extras do not state is removed on its first self-deploy** — which
is why cli-bootstrap spells the deployer's `QITS_PLATFORM_DEPLOYMENTS_*` variables in both places.

## Resources: what the deployer provisions, and where the truth is

A repository's `resources: postgresql:<name>[:<database>]` is answered before the pull, by
`ResourceProvisioning` over the `ResourceProvisioner` seam. Four things decide how it behaves and
each is easy to undo by accident:

- **The registry row is the single authority for the credential.** `pd_resource.password` is not a
  cache of something postgres knows — postgres stores a hash — and no file carries it. That is what
  makes the drift arms decidable: a row without a role is a reset postgres volume and the role comes
  back with the **stored** password (running containers keep working); a role without a row is a
  reset deployer database and the role is rotated to a **fresh** one (nothing knew the old one).
  Never a `DROP`, in either direction.
- **This component is adopter #1 and cannot provision itself from cold**, which is what
  `BootResourceRegistration` exists for: the bootstrap creates its roles and databases over plain
  JDBC before the process exists, and the rows are written from the environment at every boot.
  Without them the first self-deploy takes the reconcile arm and rotates the passwords its own
  connection pools are holding open.

  **An absent `QITS_ENVIRONMENT` is RESOLVED, not read as a plane.** It used to mean "the platform
  plane" — the swarm driver wrote the variable for environment applications only, and the rows went
  in under a null tier, which was the key `ResourceProvisioning` looked a platform service's rows up
  by. A platform service is deployed into the designated environment now and is started with the
  variable like everything else, so the absence means exactly one thing: **this container was
  started by the previous code**, in the window between the deploy that ships the change and the one
  after it. `BootResourceRegistration.environmentName()` then reads `pd_environment.platform` — the
  same designation the deploying instance used to choose where to put this container — and records
  under that name, which is the key the next self-deploy looks it up by. Recording null there would
  send that deploy down the reconcile arm and rotate both passwords this process's pools are holding
  open. Null survives only as the last resort, with a WARN, on an install with no tier designated at
  all.

  **It declares TWO resources now** — `db` (this component's registry) and `eventstream` (the bus
  client's claim ledger and outbox, a store of its own with its own Flyway lineage) — so
  `BootResourceRegistration` records both, over `RESOURCES`. A third entry in
  `.config/qits/deployments.yml` is a third line there and nothing else. **The resource NAMES are
  load-bearing**: the variables follow the name (`QITS_RESOURCE_<NAME>_URL` and its two siblings)
  and the jar that owns each store reads exactly those in its own shipped defaults, so renaming one
  here silently stops matching.

  **Self-provisioning works for everything after the first container**, and that is worth being
  precise about because this component is its own deployer: the spec is read at the released tag, so
  the *running* instance reads the new `resources:` line, creates the role and the database, and
  injects the triple into the successor it starts. Only a cold bootstrap has no deployer to do it,
  which is why the bootstrap's generated config carries both triples. A missing one is not a degraded boot —
  the jars' expressions have no defaults, so the process dies at Flyway naming what is absent, and
  the health gate leaves the predecessor serving.
- **No transaction spans the seam call.** The registry read and the row upsert are two
  `requiringNew()` brackets with the DDL between them — a socket to another server must never sit
  inside this component's own transaction.
- **The host is derived, never configured.** An environment application uses
  `PdNetworks.alias(<tier>, "qits-oci-postgresql")`; a platform-plane one uses the platform
  environment's tier, and fails with a sentence when no environment is designated. There is no
  postgres-host config key and there should not be one.

The admin credential and the untrusted-input rules for the two names are in **Untrusted input**
above. `PgResourceProvisionerTest` runs the matrix against a real postgres because the arms differ
only in which statement runs — a fake there would be asserting the test's own model of the server.

## Volumes: what the application declares, and the one shape change a deployment performs

`volumes: private:<name>:<target>[:ro], shared:<volume>:<target>[:ro]` in
`.config/qits/deployments.yml` is the `resources:` statement applied to storage. A volume mount is a
property of what an application IS — it needs a place to keep its data, in every environment it is
ever deployed into — so it belongs in the repository's own file rather than in
`qits.deployments.extras.<app>.mounts[i]`, where it was an indexed list, authored in
**another repository** (`qits-bootstrap-cli`'s `ComposeTemplate.java`), identical everywhere, and
whose only identity was the index. Four things decide how it behaves:

- **A `private` volume's NAME is DERIVED**, `<application>-<name>`, exactly as a resource's defaulted
  database is and resolved in the same place — `DeployService.register`, the first caller that knows
  the application's name. `private:data:/data` on qits-workspaces is `qits-workspaces-data`, which
  is what every private volume on this estate is already called. **A repository may never state a
  literal private volume name**: the derivation is what makes it impossible to mount a sibling's
  storage. `shared:` is the opposite case and names its volume in full out of a **closed**
  vocabulary (`qits_shared_dot_claude`, `qits_shared_m2`, `qits_shared_pnpm` —
  `qits.containers.shared-volumes` in qits-containers), because a platform-owned volume is not this
  application's to derive, and docker answers an unknown volume name by creating an empty one.
- **HOST BINDS ARE DELIBERATELY NOT IN THIS GRAMMAR.** `bind:/var/run/docker.sock:…` stays in extras
  config on qits-containers, qits-deployments and qits-platform-system. A host path is platform
  topology — a statement about the machine, not about the application — and that is the line this key
  draws. Do not "complete" the grammar later; the parser's javadoc says so for the same reason.
- **The create renders declared volumes FIRST and then drops every extras mount naming a target a
  declaration already covers.** That dedupe is what makes the migration safe rather than tidy: an
  application declaring `/data` while `ComposeTemplate` still supplies `mounts[0]` for `/data` would
  otherwise get two `--mount` flags for one directory and a `service create` that fails outright.
  With it, the declaration lands and is provably inert while bootstrap supplies the same value —
  `configuration-yml.md` §7.5's two-step rule applied to this family. It is silent by design: during
  the migration the two say the same thing.
- **A declared volume MISSING from the live service forces a `service rm` and a create**, because
  `buildUpdateArgv` states no mounts and swarm has no add-a-mount — so without it a declaration could
  never reach a running service while every deployment went green. **The rule is one-directional and
  that is not negotiable.** A declared volume absent → recreate. Anything else — a mount the service
  has and nothing declares, an application that declares nothing at all — takes the update path
  untouched. Every service on this estate carries extras-supplied mounts and declares none, so a
  symmetric "the sets differ" comparison would remove and recreate the whole platform on its next
  deployment, each service losing its writable layer.
  `aLiveServiceWithConfigMountsAndNoDeclarationsIsUPDATEDANDNOTRECREATED` is what holds that, and it
  is named so nobody deletes it. A recreate WARNs first, naming the service and the volume, and
  matches on **both** halves of a mount: the same target fed by another volume is not this volume.
  An inspect that cannot answer recreates **nothing** — `envRemovals`' stance, one step more
  strictly, since the cost here is a destroyed service rather than a stale variable. A self-update
  never recreates: there would be nothing left to create the successor.

And, like every spec key: **it must ship in the deployer before any repository writes the line**,
since a spec is read at the released tag and an unknown key fails a deployment.

## Addressing and auth

`quarkus.rest.path=/deployments/api` lives in the service module's `application.properties` and the
suite inherits it — a resource's `@Path` is relative to it and must never repeat the segment; tests
address the absolute path, which is what makes them catch a prefix regression.

**The segment was `/platform-deployments` until 2026-09-26, and this was the last route on the
estate spelling a word the platform retired.** The application has been `qits-deployments`
throughout; only the route lagged, and the pairing is what the whole change is: the segment is now
exactly what `DeployService.conventionHealthPath` derives from the application name, so this
component stopped being the one exception to its own convention.

It moved in **one commit**, which was safe for a reason worth keeping: the served prefix is baked
into the image and the spec is read at the released tag, so both halves of a self-deploy's gate come
from the same commit — a rollback to an older tag is self-consistent for the same reason. The move
carried a `health_path:` override in `.config/qits/deployments.yml` for exactly one release, because
`resolveHealthPath` asks the spec, then the STORED `pd_service.health_path` row, then the convention,
and that row still held the old path and beat the convention. **The key is gone now and must not come
back**: the row was re-registered with the served path (`GET /deployments/api/services` answers
`"healthPath":"/deployments/q/health/ready"`, which is exactly what `conventionHealthPath` derives),
so an override would only be a second spelling free to drift from the route. `OwnSpecTest` asserts
the absence.

**The retired prefix is answered by nothing, and that is the end of the cutover.** It was answered
for exactly one release by `api/LegacyPrefixReroute` — a `Filters` entry at priority 1000 (above
Quarkus' authentication handler, so the rewrite was one pass rather than two) that rewrote a leading
`/platform-deployments` onto `/deployments` and logged **one WARN per request** naming the path and
the caller. A filter rather than a duplicated `@Path` tree because half of what lives under the
segment is not JAX-RS at all: `/deployments/q` is Quarkus' own non-application root, and the
readiness endpoint under it is what a self-deploy's gate curls. **That WARN was the cutover's
inventory**, and the last caller on it was this service's own SPA; the frontend was released with the
new path as `2026.926.151155`, so the class, the second entry in
`quarkus.quinoa.ignored-path-prefixes` and the second prefix in `routes:` went together on
2026-09-26. Do not re-add a compatibility rewrite here: a prefix answered by a filter is a prefix
nobody has to migrate off.

**Nothing on this surface is open, and the role says who a caller is meant to be.** Every endpoint
carries a `@RolesAllowed`, and there are exactly three roles:

| role | endpoints | how a caller holds it |
| --- | --- | --- |
| `qits:admin` | every read — applications, deployments, the environment listing/aggregate/links, the service listing — **and the operator's two levers**, `POST /applications/{id}/scale` and `/restart` | the forwarded `X-Qits-Roles` header only: the platform edge asserts it for an authenticated admin session, and the bootstrap asserts it on its own qits-net hop (`PdApi.ADMIN_HEADERS`) |
| `qits:system` | the pins, the **deployment-request listing** (`GET /deployment-requests`, the read qits-projects draws a release's deploy phase from), every topology **write** (environment create/patch/delete, service upsert/delete) and the release intake | a machine bearer: qits-platform-idp copies `qits.idp.client.<id>.roles` into the token's `groups` claim, which quarkus-oidc reads as roles with no configuration at all. It is the open calling model's role — "a service calling a service" |
| `qits:agent` | every read — the ones `qits:admin` has, plus the pins — and no write | an agent's own bearer: a commissioned client whose kind maps to this role (`principal-bound-git-refs-plan.md`, C7) |

**The two sets — `qits:system` and `qits:admin` — do not overlap and must not.** A machine token
never carries `qits:admin`, so the read surface is a person's; a browser session never carries
`qits:system`, so the machine surface is a machine's. The role says who may hold a machine door, not
what that door reaches — under the open calling model a service calling a service may do basically
everything a machine bearer here can do. **The read half is a change** — reads were open
until the surface was protected, on the reasoning both ancestors gave — and the collector that reads
the pins is a machine peer, so it presents its own token rather than nothing.

**A read a machine peer may also ask is not a hole in that, and there are two of them now.** The
pins were the first; the deployment-request listing is the second, because qits-projects draws a
release request as one pipeline of three phases — QA, publish and **deploy**, which is this
component's request for a (repoId, version) pair — and it is a platform peer rather than a person
or an agent. Without the grant its only way in would be to forward somebody's session headers, a
service impersonating a user to read a listing it is itself entitled to. What the non-overlap
forbids is a TOKEN holding both roles, and that is untouched: no machine bearer carries
`qits:admin`, the rest of the read surface is still a person's, and the operator's levers on
`PdApplicationController` — scale, restart and decommission — stay `qits:admin`-only, because
nothing on the platform should be able to stop or retire an application as a side effect of holding
a service token. Granting one read says who may ask it; it says nothing about what is answered.

**Where `machineAuth.require()` goes** is the same question one layer in: on the paths whose callers
are machines — the intake and every topology write. It re-asks the audience question the token
already passed at `quarkus.oidc.token.audience`, so the annotation and the guard fail
independently; apply the same question to a new write and answer it in the commit that adds it.

The guard is **gated off** by `qits.auth.machine.required` (default `false`, shipped by
`qits-auth-core`). Validation follows the same gate
(`quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}`) — gate off, there is no OIDC
tenant, nothing fetches a JWKS, and a clone-alone build needs no issuer. There is no third state.
`@RolesAllowed` does **not** follow that gate: it is on in every posture, and what keeps a
credential-free `./mvnw test` green is `qits-auth-core`'s `%test` dev user, which is granted every
platform role the library ships, `qits:admin` and `qits:system` among them.

**Three doors, and knowing which shut is how a grant is debugged.** A token for another service is
refused 401 by `quarkus.oidc.token.audience` before any identity exists; a token addressed here but
minted by a client with no `.roles` line authenticates and is refused **403** by `@RolesAllowed`; a
call with no credential at all is 401. `MachineGuardEnforcedTest` pins all three.

The intake path is a **cross-repo contract**: qits-ci POSTs
`/deployments/api/events/software-released` fire-and-forget (and the bus is the ordinary door). A
mismatch raises no error anywhere. Move one, move both — **and note that the contract has no live
sender on that side today**: qits-ci carries neither an intake url nor a release notifier any more
(its own `application.properties` says so in as many words — nothing in it calls the deployer, so
there is no address of the deployer in that file at all), and the ordinary door is the bus. So the
path is a contract with whatever replays it — the bootstrap and an operator — rather than with a
configured client, and there is no compatibility rewrite standing behind it any more.

**A new machine surface outside `/deployments` needs a line in
`quarkus.quinoa.ignored-path-prefixes`, in the same commit.** Quinoa's SPA fallback is a catch-all
registered near-last, so a real route still wins — but a path matching *no* route is rerouted to
`index.html` and answers `200 text/html`, which a machine client parses as data. Setting the key
**replaces** Quinoa's derivation rather than extending it, and the values are matched **after**
`ui-root-path` is stripped — which is `/` here, so they are written **absolutely**. The first entry
is `/deployments`, and it covers `/api` and `/q` by prefix; a route outside that segment needs its
own. `@WebSocket` or anything on the Vert.x router takes a literal path and needs one too.

**The second entry is `/platform-deployments`, the RETIRED prefix, and it is permanent.** This list
is not "the prefixes this service serves" — it is "the prefixes a machine may dial", and a retired
one belongs on it while anything might still ask for it. It was removed in the commit that deleted
the reroute, on the reasoning that it only ever covered the one failure the rewrite could not, and
**that was wrong and measured wrong on the deployed `2026.926.170022`**: while the reroute existed it
rewrote the prefix before Quinoa could see it, so only a legacy path naming no route reached the
catch-all; with the reroute gone, *every* path under the prefix names no route, so the catch-all
claimed the lot and `/platform-deployments/api/environments` answered `200 text/html` with
`index.html`. That is precisely the defect qits-380 was filed about, recreated by tidying the line
away. `routes:` staying single-entry is right — the edge should route nothing there — and it protects
nothing here, because every machine caller of this service dials the wire alias and never traverses
the edge. Taking the entry out again needs positive evidence that nothing dials the old prefix, which
no log can give now that the reroute's WARN is gone.
`PdPackagedSurfaceIT.aPathUnderTheRetiredPrefixIsNeverTheClient` is the guard.

## The client, and where the segment lives

`service/src/main/webui` is the **qits-deployments-frontend** submodule. This service has a host of
its own, so the client is served at `/` and its `angular.json` sets `baseHref: /` — there is no
segment in the client at all. Its calls in `api/cd-api.ts` were `/platform-deployments/api/…`
**hardcoded**, and that repository did not move with this one: for one release every page load of the
client was a WARN in this service's log, which is what `api/LegacyPrefixReroute` existed to count. It
was the last entry on that inventory, and the frontend was released with the new path as
`2026.926.151155` — which is what let the reroute be deleted. The gitlink here is bumped by the
ordinary maintenance path, not by this change.

The LIVE segment is spelled in **four** places, all of them in this repository:
`quarkus.quinoa.ignored-path-prefixes`, `quarkus.rest.path`,
`quarkus.http.non-application-root-path` and `routes:` in `.config/qits/deployments.yml`. Three
spellings of the RETIRED one existed for the length of the cutover and came out on 2026-09-26 — the
second `routes:` entry, the `health_path:` override and `api/LegacyPrefixReroute` — and **a fourth
stays for good**: the second value in `quarkus.quinoa.ignored-path-prefixes`, which is what makes the
retired prefix a 404 rather than the client now that nothing rewrites it. See the section above for
why removing it shipped the defect once. `PdPackagedSurfaceIT` probes the served base href, the
scoped deep link, `/deployments/` answering 404 rather than a second copy of the client, a path under
the live prefix naming no route answering 404 rather than the client on BOTH surfaces under it, and
the same of the retired prefix.

## The event side: a RELEASE is the only trigger

**A green build deploys nothing any more.** `ReleaseAnnouncements` in `deployments/control` is the
seam for what comes IN, and what comes in is a released version: qits-ci minted a CalVer stamp,
pushed the tag, published `qits/<app>:<version>`, and announced it. `BuildAnnouncements`, `BuildTips`
and `bus/PdBuildSuccessfulSubscriber` are **deleted**, not dormant — two doors keyed on two
coordinates would deploy one application twice and race each other's cutover. (What goes OUT is
`DeployAnnouncer`, below.)

- **The bus** (`bus/PdSoftwareReleaseSubscriber`), a `QitsDurableEventListener` on qits-ci's
  `SoftwareRelease`. The publisher retries it, the log replays it after a cutover, and the library
  hands it over exactly once per event whichever channel delivered it.
- **`POST /deployments/api/events/software-released`** (`api/PdEventController`). The
  **manual and bootstrap** door: a bootstrap replays a lost release, an operator redeploys a version
  or deliberately goes back one, and it is the only one that works before qits-events exists. Nobody
  retries it. `/events/build-succeeded` is **gone** and 404s.

Five things about the subscriber that are the whole of what a durable consumer owes:

- **Only `packageType: "docker"` is even looked at.** One release publishes a jar, an npm package, a
  docs bundle and an image as four events; only the image names something this component can put
  live, so `selects` answers false to the rest and they are stored nowhere at all.
- **The application name comes out of `packageName`, not out of a repository.** A `SoftwareRelease`
  carries `repoId`, `repository` (the same string) and an optional `projectId`, and **no repository
  name at all**. What it carries is the package, registry-unqualified — `qits/qits-ci` — and
  `PackageNames.applicationOf` takes the last segment. The repository travels beside it
  **id-addressed** (`RepositoryRef(repoId, projectId, null)`), for the spec read alone. `projectId`
  is identity enrichment and never a key: absent is tolerated and deploys identically.
- **`consumerId()` is `pd-software-released`, and it is storage.** Deliberately a NEW id rather than
  the retired `pd-build-succeeded` renamed — the old ledger is a watermark measured in
  `BuildSuccessful` rows and says nothing about this consumer's work — and **`replayFromEpoch()`
  returns `false` explicitly**, so the consumer initializes at the HEAD of the log. That override is
  a statement rather than a change: replaying from the epoch would, on the first boot after this
  ships, redeploy the platform's whole release history in log order. `PdBusReleaseIntakeTest` pins
  both.
- **Ordering is ours and is not optional.** Catch-up delivers late, so a *different, older* release
  can arrive after a newer one is live — a rollback nobody asked for. `ReleaseTips` collapses to the
  tip, per application, and takes two answers: what THIS PROCESS announced (a map, exact), and, only
  when that knows nothing, the **newest deployment REQUEST row** for that application (the
  cross-restart floor). The request rather than the deployment, because a request is written the
  moment a release is accepted — there is no minutes-later skew to reason about, and both sides of
  the comparison are version strings. **`Versions` compares them segment by segment, numerically**,
  and that is load-bearing: the stamp is unpadded, so `2026.731.193059` (19:30) sorts BEFORE
  `2026.731.93059` (09:30) as text and a lexical comparison would read the later release as the
  older one. Duplicates need nothing — the library already makes the same event id impossible twice.
- **A throw leaves the event owed forever.** It is offered again on every sweep and the watermark
  stays behind it, so one poison event stops this consumer's catch-up. So the handler **swallows
  what retrying cannot fix** — an unreadable payload, a package naming no application, an identifier
  `DeploymentIdentifiers` refuses — each with a WARN, and throws only what a next attempt could
  succeed at.

**Two facts about the transaction the handler runs in.** It is the library's claim transaction, on
the `eventstream` datasource — so every read of *this* component's database inside it takes a
`QuarkusTransaction.requiringNew()` of its own (two non-XA resources in one transaction is a thing
Narayana refuses), which is what `ReleaseTips` does. And `announce` returns as soon as the release
is queued, which it must: the handler is holding that transaction open while it runs.

### The bus's durability ends at the handler — `pd_owed_release` carries it the rest (2026-09-05)

**The library claims a `SoftwareRelease` the moment `onFrame` returns, and `announce` returns as
soon as the release is a `Runnable` on `pd-deploy-worker`.** That queue is in memory, it is
serialized platform-wide so it is legitimately an hour deep, and `DeployService`'s `@PreDestroy`
calls `shutdownNow()` on it. So a cutover of this component — which it performs on itself,
`stop-first` — dropped the whole queue while the bus's ledger said, correctly, that the events had
been handled: the successor's catch-up found the claims and skipped them, and no deployment request
was ever written. **Seven applications went that way between 13:00 and 17:15 UTC on 2026-09-05.**
Four were replayed by hand through the manual door; three were rescued only because a later release
folded their tags. Nothing on the bus will ever offer such an event again — that is what makes it a
loss rather than a delay.

`ReleaseAcceptance` + `OwedReleaseSweep` + `pd_owed_release` (V10) are the second ledger. The bus
door calls **`ReleaseAnnouncements.announceDurably`**, which validates, writes the obligation, and
only then queues; the worker settles it when `deploy()` has returned. Six things about it, each easy
to undo by accident:

- **It cannot commit with the bus's claim and does not try.** `consumed_event` is in the
  `eventstream` database and this row is in `platformdeployments`; both datasources are non-XA and
  Narayana refuses two in one transaction — the note `ReleaseTips` already carries. So the ORDER is
  what is chosen: **obligation first**. What that leaves is a duplicate (obligation written, claim
  rolled back) and never a loss, and a duplicate is what the re-drive guards collapse to a no-op.
- **The re-drive predicate is `accepted_by`, a uuid minted per JVM**, and it is exact rather than
  heuristic: a row carrying this process's id is on its queue and is left alone however long it sits
  there; a row carrying anybody else's belonged to a queue that did not survive. **A time-based
  predicate cannot work here** — any grace period longer than a legitimate hour-deep queue is longer
  than the outage it would be covering. Null is "held by nobody", which is what the worker leaves a
  row it could not discharge in, so this process's own next tick retries it.
- **`instanceId` is an INSTANCE field, not a static one.** `service/` compiles to a native image and
  a static initializer is evaluated at build time and baked into the image heap — every container of
  one image would then share one "process" id and no row would ever look orphaned.
- **The sweep re-drives through the SAME door and never around it.** A version already requested is
  settled `ALREADY_REQUESTED` (the idempotence guard, asked of `pd_deployment_request` for
  `ReleaseTips`' reason); one a newer release has overtaken is settled `SUPERSEDED` rather than
  deployed, which is the one thing a re-drive must never be able to cause; everything else is an
  ordinary `announceDurably` — same identifier validation, same spec read at the released tag, same
  `Door.RELEASE_EVENT` refusal of a release that declares no file.
- **`announceDurably` is a second method rather than the default, because `Door.MANUAL` does not want
  it.** That door is an operator naming a version on purpose, including a rollback, and a ledger that
  re-announced one after a restart would be re-answering a question a person already answered — and
  would then have it refused by the monotonic collapse for good measure. It **implies**
  `RELEASE_EVENT` rather than taking a door: the obligation and the door are one decision.
- **A release held on an unreadable spec is NOT settled**, and a `deploy` that threw is handed back
  rather than settled. `specRetries` lives in a map that dies with the JVM, so an obligation still
  held there has to outlive the process that is holding it. `MAX_ATTEMPTS` (5) bounds the whole
  thing: an obligation that fails identically on every boot is parked `EXHAUSTED` with an ERROR
  naming the manual door, rather than announced at every boot forever — the poison-event shape the
  bus library warns about, one layer down.

**The tick is the belt and the boot pass is the cure**
(`qits.deployments.owed-release-sweep-seconds`, 60; `0` keeps the boot pass and switches
the tick off). It is a plain daemon thread — `pd-owed-release-sweep`, the observation ticker's shape
— and not `quarkus-scheduler`, for the reason `DeploymentObserver` gives: this component's whole
ordering story is "one worker, in queue order". Nothing is done on that thread but the reading and
the guards; every announcement lands on the deploy worker.

**Its observer runs at `APPLICATION + 600`, and that is a correctness requirement.** CDI runs
observers in ascending priority, and `DeployService.onStart` — default priority — runs
`sweepInFlight()` synchronously: it reads every `QUEUED`/`STARTING` row and settles each as
interrupted by the previous shutdown. A re-drive queues rows of exactly those statuses. Started
first, this sweep could put a fresh `QUEUED` row in front of that read and have the predecessor's
cleanup settle a deployment that is beginning rather than one that was interrupted.

**No story or `@QuarkusTest` runs the ticker** — `onStart` returns early in `LaunchMode.TEST`, the
observer's arrangement — and `PdOwedReleaseTest` drives `sweep()` directly, staging a dead process by
writing its row rather than by killing a JVM. `PdSchemaTest` holds the storage half: one obligation
per `event_id`, and a settled row is invisible to the owed query.

### The second consumer: `pd_owed_release` stores an ADDRESS, and an address can be renamed (2026-09-07)

**qits-projects renames a repository and this component's durable ledger keeps the dead name.**
`PATCH /projects/api/repositories/{repoId}` changes a repository's public identity and announces
`RepositoryRenamed` — `{projectId, repositoryId, oldName, newName, renamedAt}`, five fields, with
`eventId`/`occurredAt` kept out of the canonical payload by the library's own mix-in. Nothing moves
on the git host (the bare is keyed by the opaque storage id), so `/git/<projectId>/<newName>` serves
the blob the moment that row commits and the old name serves nothing ever again. **Two renames
happened live on this platform that day**, which is what made the hazard a defect.

`repo_name` on `pd_owed_release` (V10) is the one durable place in this schema that stores a
repository name, and `OwedReleaseSweep` rebuilds the *whole* announcement from that row — another
process, after a cutover, with the event long since claimed and nothing left to ask. So a held or
owed release of a renamed repository keeps the old name, `RepositoryRef` sees both halves of a
public address and takes the name-first route, and the spec read addresses a URL nothing serves.
**The id-route fallback does not rescue it**: qits-githost's storage-client guard refuses
`/git/<repoId>` to every caller that is not qits-projects, and a 403 is classified retryable — so it
becomes a sixty-minute `SPEC_UNREADABLE` hold for nothing.

`bus/PdRepositoryRenamedSubscriber` is the correction, and it is the component's **second**
`QitsDurableEventListener`. Six things about it, each easy to undo by accident:

- **`consumerId()` is `pd-repository-rename`, a NEW id and storage.** It shares nothing with
  `pd-software-released`: that ledger is a watermark measured in `SoftwareRelease` rows, and the two
  answer `replayFromEpoch()` differently, which one watermark could not express for both. The
  signature is the literal `"RepositoryRenamed"` — **no compile-time dependency on qits-projects**,
  whose event class lives in its own `service/…/bus/` and in no published jar. The cost is that a
  rename over there is silent here, which is the cost every cross-repo contract in this component
  already carries.
- **`replayFromEpoch()` returns TRUE, where the release consumer returns `false`, and the asymmetry
  is the whole reason each states its answer.** That one replays DEPLOYMENTS — from the epoch it
  would redeploy the platform's entire release history in log order. This one replays a **name
  correction**: a projection repair, which is exactly what the library reserves the answer for. A
  fresh consumer starting at the head would miss the two renames of 2026-09-07 — the ones it was
  written for; `signatures()` bounds the replay, because catch-up queries the log **with** the name
  filter rather than paging all of it; and the write is a converging UPDATE keyed by repository id,
  so A→B then B→C in log order lands on C. It is consulted at initialization and never again, so a
  deliberate later re-run is `CatchupSweeper.rebuildFromEpoch(CONSUMER_ID)`, which clears both the
  watermark and the claim ledger first.
- **`selects` is left at the default**, everything the signature matched. There is no content
  narrowing to make — whether a rename matters cannot be known without the query `onFrame` makes
  anyway — and an override that decoded to answer would be an override that can **throw**, which the
  library treats as a failure rather than a "no": the event would stay owed over a question about
  the event instead of over the work.
- **The write is `ReleaseAcceptance.renameRepository` and it is bracketed there, not in `bus/`.**
  The handler runs inside the library's claim transaction on the `eventstream` datasource, and two
  non-XA resources in one transaction is a thing Narayana refuses — `ReleaseTips`' note. So the
  update takes a `QuarkusTransaction.requiringNew()` of its own on the deployments datasource, via
  `DbRetry.inNewTx` like every other write on that ledger. The consequence is the `ReleaseAcceptance`
  trade in miniature: the rename commits before the claim, so a rolled-back claim replays a
  correction that has already been applied — which costs nothing, because the statement converges.
- **It moves EVERY row of that repository id, settled ones included, and fills `project_id` only
  where it is null.** The column is the address the spec is read at, not a record of what the
  repository was called at acceptance time: an address that no longer resolves is worth nothing as
  history, and the settled `EXHAUSTED` row is precisely the one a person opens when they go looking.
  `RepositoryRef.nameAddressed()` needs both halves, so a row with a name and no project still falls
  back to the refused id route and is worth completing — but a rename is not a MOVE, so a row
  already naming a *different* project is not this event's to correct. **`DeploymentIdentifiers`
  is deliberately not run on the incoming name**: this column is storage, the read path validates it
  when it builds a `RepositoryRef`, and refusing the event here would leave a stale name — one that
  certainly no longer resolves — in place of a fresh one.
- **Nothing else in this schema stores a repository name, and that was re-checked rather than
  assumed.** `pd_deployment_request` (V6) carries `repo_id` and `project_id` and no name;
  `pd_deployment`, `pd_service` and `pd_resource` record an **application** name and no repository
  identity at all, which V1's header states as the rule. **An application name is not a repository
  name** and must never be rewritten from one — `application:` in the spec exists so a repository can
  be renamed with nothing on the platform moving.

**`DeployService.specRetries` is deliberately NOT touched, and that is the decision.** It is an
in-memory `Map<String, SpecRetry>` keyed by the released name, each entry carrying a `RepositoryRef`
with exactly this staleness — but every entry expires within `SPEC_RETRY_DEADLINE` (≤60 minutes) and
the map is the deploy worker's, which is the one thread that reads it. A bus listener poking it from
inside the library's claim transaction would add a race for an hour of staleness that resolves
itself: a held release that expires re-announces from the ledger, which by then names the right
address. The rows are what survive a restart, and the rows are what this door corrects.

`PdRepositoryRenameTest` holds all of it, driving `onFrame` directly and staging `pd_owed_release`
rows by hand the way `PdOwedReleaseTest` stages a dead process. `PdEventstreamDarknessTest` asserts
this listener survives ArC's unused-bean removal beside the release one — **per listener, never as a
count**, so the commit that adds a third door does not go red for a reason that is not a defect.

### The deployment REQUEST, and the gate in front of the queue

`pd_deployment_request` (V6) is the row a release writes **before** anything is queued: application,
version, environment, the package and repository it came from, the quality gate and its detail, and
— once the gate is met — the `deployment_id` it handed off to. The environment is the tier the
release entered at, **including for a platform service**: the plane deploys into the designated tier
since V8, so the request names it exactly as the deployment does. (The column is still nullable, for
rows written before that.)

- **The request points at the deployment, never the reverse.** The request is the cause and is
  written first, so it cannot hold a key to a row that does not exist yet, and `pd_deployment` keeps
  the exact shape every existing reader of it has.
- **Both rows are written in ONE transaction, with the gate answered between them.** A request with
  a settled gate and no `deployment_id` therefore means *refused*, never "the process died in
  between".
- **`DeployService.gate` is a placeholder that says yes to everything**, and `PdQualityGate.UNMET`
  is unwritten today. That is the point of both existing now: the shape has to be in the schema and
  in the queueing transaction before a real gate has an opinion, or the first one arrives as a
  migration over live history. Whatever asks a question later answers in that one method — it takes
  the release AND the target, because a real gate is about a (version, place) pair.
- **It is a read surface too** (`PdDeploymentRequestController`, `qits:admin` + `qits:agent` +
  `qits:system` — the last so qits-projects can draw a release's deploy phase without forwarding a
  person's session headers; see *Addressing and auth* — with every read
  wrapped in `PdReadPatience`), and it is a separate resource from the deployment listing rather
  than a richer answer to it, for the reason the table exists at all: **a refused request queues
  nothing**, so it has no deployment row to be seen through, and a client reading only the
  deployments would draw that release as never having happened.

  **Three filters, asked in ORDER, and the order is the API.** A request is one row three different
  screens reach by three different questions:

  | filter | answer |
  | --- | --- |
  | `?environmentId=` (`&applicationName=` optional) | one tier's, newest first — the front page's fold-by-name |
  | `?projectId=` | one project's: **every pending request plus the ten newest settled**, capped in `DeployService` |
  | `?repoId=&version=` | the exact-match join a release page follows — **both or neither**, half is a 400 |

  None of them is a 400 too. `GET /deployment-requests/{id}` sits beside the listing and **inlines
  the deployment**, because there is no deployment-by-id endpoint and minting one to serve a single
  screen would be a wider surface than the screen is.

  **An unknown PROJECT is an empty 200 where a missing TIER is a 404**, and the asymmetry is the
  honest one: a tier is this component's own row, a project is qits-projects' and this schema holds
  none — `project_id` sits beside `repo_id` as a foreign identity resolved by nobody. Say that in
  the javadoc of anything that adds a filter over a foreign identity.

  Note it takes **no `platform` value** where the deployment listing does — a request records no
  plane, and a platform service's request names the tier it deploys into, so it comes back in that
  tier's answer. `PdDeploymentRequestDto` deliberately derives no `applicationId`, because deriving
  `platform:` or `<tier>:` from a row with no plane column would be a guess; the frontend folds each
  request into its application's row by NAME.
- **Every request DTO carries `deploymentStatus`, joined from the row it points at**, on every path
  including the tier listing. The question a reader asks of a request spans two rows — the gate is
  on the request, the container is on the deployment — and neither answers it alone. Null is a real
  answer: a refusal queued nothing, and an environment teardown forgets deployment rows while the
  requests outlive them by design. **The join is one batch query per listing**
  (`PdDeploymentRepository.listByIds`), never a fetch per row, and `DeploymentMapper.toDto` takes
  the deployment as a nullable ARGUMENT so it cannot become one.
- **`priority` is RECORDED and acted on by nobody, and that is the whole feature for now** (V11).
  qits-projects declares a priority per participating BRANCH of a release request (`LOWEST` …
  `BLOCKING`, effective value = the max over the branches), qits-ci transcribes it onto
  `SoftwareRelease`, and this component writes it on `pd_deployment_request`. **The deploy worker is
  FIFO and stays FIFO** — queue ordering is the next feature and this column is what it will read.
  Four things about it, each easy to undo by accident:
  - **A display-only `String`, no enum, no check constraint.** The vocabulary is qits-projects' and
    a copy here would be a second spelling of somebody else's decision — the no-shared-vocabulary
    stance the rest of the intake path already takes. A word this build never heard of is stored.
  - **Nullable everywhere, never backfilled.** Null is "the release stated none": every release cut
    before the field existed, every event replayed from before it, and every manual-door POST that
    did not name one. `CanonicalJson` is `NON_NULL`, so on the wire the absence is a missing key and
    the subscriber must tolerate it **forever**, not merely across a rollout.
  - **It can never refuse a release.** `deployments/control/ReleasePriorities` is a LENIENT helper —
    trim, blank → null, longer than the column → null and a WARN — deliberately not
    `DeploymentIdentifiers`, which throws and guards values that reach an argv, a URL or an image
    path. This one reaches a column and a DTO field, so an advisory badge failing a green release
    would be the regression.
  - **`pd_owed_release` carries it too, and that is the load-bearing half.** `OwedReleaseSweep`
    rebuilds the whole announcement from that row — another process, after a cutover, with the event
    already claimed — so a field the acceptance ledger does not store is a field every re-driven
    release silently loses. Anything added to `ReleaseAnnouncements.announce` goes in both.
- **`control/RequestLifecycle` is the single spelling of pending-versus-completed**, and it states
  the rule as a POSITIVE list of the statuses that are still moving (`QUEUED`, `STARTING`,
  `SPEC_UNREADABLE`) plus an unanswered gate. `PdDeploymentStatus` is a varchar with no check
  constraint precisely so the vocabulary can grow, so a `!= ACTIVE`-shaped rule would read the next
  terminal word as in-flight. **The SPA mirrors it** (`isCompletedRequest` in the frontend's
  `api/dto.ts`) because the client draws the two sections and decides whether to keep polling; move
  one, move both.

### Where a release lands: the ENTRY TIER, not a branch

**Branch matching is gone from the intake path.** A release names a tag, not a branch, so the tier a
version enters is a property of the platform: `DeployService.entryTiers()` answers with the
**designated platform environment** (`pd_environment.platform`, of which there is exactly one), and
that is the whole of what replaced `tiersOnBranch`. Empty is a real answer — a mid-bootstrap install
registers nothing and deploys nothing rather than picking a tier at random, which is the answer both
retired register arms always gave. The link set written is still the **union** of what the catalogue
holds and the entry tier, so a tier a promotion already reached is never unlinked.

**`pd_environment.branch` is GONE** — the column (V8), `EnvironmentService.BRANCH_PREFIX`,
`onBranch`, `listByBranch`, the create/patch payload fields and the DTO field with it. An older
sender's `branch` still deserializes into nothing rather than failing a creation, which is the only
compatibility owed. `PdIdentifiers.requireBranch` stays: `pd_service.branch` (vestigial, written
null by derived registration, settable over the operator's `PUT`) and the parser's `deploy_branches`
tolerance are its callers. A promotion ladder is the follow-up this shape waits for: a version
promoted to the next tier is a deployment request of its own against another environment, which is
the row that now exists. **What must not come back is a branch.**

### The platform plane is DELETED (2026-09-24)

**Three sections lived here and they are one.** They were *…and the PLATFORM PLANE lands there too*
(2026-09-03, the plane gaining a tier), *A plane conversion retires its tier services* (2026-09-07,
the `dev-qits-configuration` orphan) and *The plane is the catalogue's answer, not the file's*
(2026-09-23, the incident where the deployer refused to deploy its own fix). Each was a correction to
a distinction that no longer exists, so this is what replaced all three.

**There was one plane too many and now there is one.** `PdDeploymentTarget` is gone — the enum, both
columns (`pd_service.deployment_target` and `pd_deployment.deployment_target`, V13), and every branch
that read either. A service is defined by its **links**: the environments it is registered into. It
used to be possible for a service to carry NO link and mean "present in every environment", and that
absence was the whole mechanism of the plane — an environment created tomorrow picked up
qits-platform-idp with nobody editing a row. It now means what it says: a service linked nowhere runs
nowhere.

What that deleted, in one list, because each entry is a thing somebody might reasonably try to
re-add:

| was | is |
| --- | --- |
| `PdNetworks.alias(target, env, app)` — bare on the plane | `alias(env, app)`, `<env>-<app>`, unconditionally |
| `PdNetworks.platformAlias(app)` | deleted |
| `PdNetworks.additionalAliases(...)` | deleted — it granted the qualified name beside the bare one, which was the staged first half of exactly this change |
| `PdNetworks.PLATFORM` (`qits-platform`), `NetworkKind.PLATFORM` | deleted; the swarm topology is ONE overlay, the flat one |
| `ContainerNames.of(target, ...)` — segment dropped on the plane | `of(env, app, id)`, always qualified |
| `ApplicationKeys.PLATFORM` / `isPlatform` / `of(target, ...)` | `of(env, name)`, keys `<env>:<name>` |
| `GET /deployments?environmentId=platform` | gone; `platform` names no tier and answers 404 |
| docker label `qits.platform.deployments.target=platform` | written by nothing, read by nothing |
| the teardown's `target=environment` filter | the environment label alone — it existed only to stop a tier's teardown taking the plane with it |
| `ServiceCatalog`'s "a platform service carries no links" 400 | nothing; links are stored |
| `ServiceCatalog`'s platform→environment 409 and the environment→platform conversion | nothing; an upsert replaces the link set, in both directions |
| `DeployService.registerPlatform`, `retireConvertedTierServices`, `owedTierRetirements` | deleted; `register` has one arm |
| `PdServiceRepository.listPlatformServices`, `PdDeploymentRepository.listPlatformNewestFirst`/`listEnvironmentScoped` | deleted |
| `DeclarationSeed.seed(app, version, target, yaml)` | `seed(app, version, yaml)`; the wire parameter is the constant `environment` |

**`pd_environment.platform` STAYS, under that name.** It is not the plane — it is the designation of
the tier a release ENTERS at, which is still a real question with exactly one answer
(`DeployService.entryTiers()`). It is renamed in a later feature; do not touch it here.

**`pd_deployment.deployment_target` had to be DROPPED rather than left behind**, and that is not
tidiness. V8 made it `not null` with no default, on the rule that every writer states the plane; the
writer is deleted, so a column left in place would fail every INSERT of a deployment row from the
first release onwards.

**V13 converts the nine, and a migration that only dropped the column would have lost them.** Those
rows say `PLATFORM` and carry no `pd_service_link` at all, because carrying none WAS how the plane
spelled itself — so dropping the column alone leaves exactly the nine services the platform is built
out of linked nowhere, which after this change reads as "runs nowhere": out of the link query, out of
the tier's aggregate, and out of the deployer's management. V13 links each of them into
`pd_environment.platform`, the tier the plane already deployed into since V8, which is why the
translation is decidable rather than a guess. `PdSchemaTest` holds both halves — the conversion, and
the undesignated install where the cross join answers no rows and nothing moves.

### Retiring the plane's bare-named services — the rollout's hand steps

**Read this before releasing the change, not after.** A swarm service's name IS its wire alias, the
nine that were the plane are running under their BARE names, and this build derives `<env>-<app>` for
them. So the first deployment of each finds no service of that name, **creates** one, and leaves the
bare-named predecessor running.

**Nothing in this component retires it, and the reason is worth knowing rather than working around.**
A predecessor is found by name (`SwarmDeploymentDriver.apply` → `serviceExists(spec.wireAlias())`) and
the name is what changed; the cutover's reap cannot help either, because `reap` is a **no-op** under
swarm — a replace is ordinarily an update of the same service, so there is nothing to remove. And the
retirement machinery a plane conversion used (`owedTierRetirements` → `removeService`) read the names
off the deployment rows, which already record the address that is moving: driving it from here would
remove the service the deployment just created. So the predecessors are an operator's, one line each:

    docker service rm <app>          # e.g. docker service rm qits-ci

**The ORDER matters for anything that publishes a port — IN EITHER MODE — or holds a single-writer
store.** Host-mode publishing binds the port from inside the task, so the successor's task sits
`Pending` on a port the predecessor is still holding. **Ingress mode is NOT the exception it looks
like:** the routing mesh holds the port for the whole service, and swarm refuses a *second* service
that publishes the same one — `port '8080' is already in use by service ...` — at CREATE time, which
is the path this rename takes. So an ingress publisher's predecessor has to go first as well; it
merely fails differently, with a refused create instead of a Pending task. And two tasks on one
postgres volume is the WAL corruption this repo has already paid for twice. For all of those: remove
the bare-named service **first**, then let the release deploy. For a stateless service with no
published port the order is free and the predecessor can go afterwards.

**Which of the nine that is, measured 2026-09-24 off the live extras rather than guessed:**

| service | why it must go first |
|---|---|
| `qits-platform-edge` | publishes `8080:8080` and `443:8443`, ingress; holds `qits-edge-letsencrypt` |
| `qits-deployments` | holds `qits-deployments-config` and binds the docker socket |
| `qits-platform-system` | holds `qits-platform-system-config` and binds the docker socket |

The other six — idp, configuration, events, mirror, orchestrator, maintenance — publish nothing and
mount nothing, so nothing forces their predecessors to go first.

**But consider removing all nine first anyway, and check this before deciding.** The reason to leave
a predecessor running was that the bare name still had callers. After qits-350 it should have none:
every dialer derives `<env>-<application>` from `QITS_ENVIRONMENT`, and the `iss` claim — the last
thing spelled with a bare alias — is a string that is COMPARED, never resolved. If that holds, a
predecessor left running serves nobody, and leaving it costs something real: **it carries the
`<env>-<application>` network alias too**, so for as long as both exist that name round-robins
between the old image and the new one. Two live instances of a service is a state to enter
deliberately, not as a side effect of an ordering rule.

It is survivable where the work is claim-guarded — `GcSchedule` refuses a second run through the
store's active-run check, not merely through per-JVM `ConcurrentExecution.SKIP` — but that is one
component's guarantee and not a property of the six.

Verify the premise rather than trusting it — and **do not verify it by diffing env KEYS against
config**, which is the obvious check and the wrong one. A container's environment is frozen at
creation, so a service that deployed BEFORE the entries were corrected holds the old VALUE under a
key that config now states correctly: the key diff comes back clean while the container still dials
the bare alias. The system API exposes env keys and never values, so nothing about the live spec can
tell those two apart.

**The reliable signal is time, not shape: every consumer must have DEPLOYED since the entries were
corrected.** The qits-350 entries were corrected on 2026-09-24 at roughly 15:30 UTC (qits-configuration
revisions ~424-451). So:

    GET qits-deployments:8080/deployments/api/deployments?environmentId=<id>
    # for each applicationName, the newest ACTIVE row's createdAt must be AFTER the correction

Any application whose newest deployment predates it is still carrying bare addresses and must be
redeployed before the bare-named services are retired. Known to be in that state at the time of
writing: **qits-artifacts**, which deployed at 14:42:37 and whose entries were corrected afterwards.

The direct reading, if you have a shell on the node, settles it for a single container and is worth
it for the ones you are about to strand:

    docker exec <container> env | grep -E 'qits-(platform-)?(idp|mirror|events|configuration|maintenance|orchestrator|system|deployments)'

Anything printing a BARE alias is a container that breaks the moment its predecessor goes.

**THE EDGE IS CIRCULAR AND NEEDS ITS IMAGE ON THE NODE FIRST.** It fronts the registry, so removing
it takes away the very route a `docker service create` would pull the successor's image through —
the same circularity `publish_mode: ingress` was adopted to escape for ordinary redeploys, and it
comes back here because a rename is a create rather than an update. Pull the image BEFORE removing
anything and create with `--no-resolve-image`, which is what the 2026-09-24 rollback of this service
did for exactly this reason:

    docker pull registry.dev.localhost:8080/qits/qits-platform-edge:<version>   # while the edge still serves
    docker service rm qits-platform-edge
    docker service create --name dev-qits-platform-edge --no-resolve-image \
      <every flag from `docker service inspect qits-platform-edge --pretty`> \
      registry.dev.localhost:8080/qits/qits-platform-edge:<version>

Between those two lines the platform has no published listener at all. It is the one step of this
rollout with a real outage window, and it wants a person watching rather than a script.

**qits-deployments cannot do this to itself, and that is the one step that is not optional.**
`SwarmDeploymentDriver.apply` exempts a self-update from the recreate path by comparing
`ownServiceName()` against the wire alias — and after this change those two strings differ
(`qits-deployments` against `dev-qits-deployments`), so the exemption does not fire: the deployer
would create a second service beside itself. It therefore moves by hand, from an admin workspace,
after the release is published and before it is announced to this component:

    # 1. read what the running service was created with, and keep it
    docker service inspect qits-deployments --pretty

    # 2. rename it, which under swarm is a remove and a create — there is no rename
    docker service rm qits-deployments
    docker service create --name dev-qits-deployments \
      <every flag from step 1: --network, --mount, --publish, --env, --label, --update-order, --health-*> \
      qits-artifacts:8080/qits/qits-deployments:<the released version>

    # 3. confirm it answers on the new name before anything dials it
    docker run --rm --network qits-net curlimages/curl -sf \
      http://dev-qits-deployments:8080/deployments/q/health/ready

**Which prefix that probe names depends on which version you are moving.** The route moved to
`/deployments` on 2026-09-26; a version cut before that serves `/platform-deployments` and only
that, the single release in between served both (the second by way of `api/LegacyPrefixReroute`),
and everything from the release that deleted the reroute on serves `/deployments` and only that. So
the line above is right for anything current and wrong for a rollback — check the version before
believing the path, the same way every other command in this section checks the service name.

Everything that dials the deployer moves with it — the `QITS_PLATFORM_DEPLOYMENTS_*` addresses in
qits-bootstrap-cli's `ComposeTemplate` and in qits-configuration's entries — and those are the wrapper's
to land, not this repository's.

### The application name is the repository's NAME, never its storage id (2026-08-21)

qits-githost is dumb blob storage now: its repository key is an opaque UUID and `/git/<uuid>` is an
internal URL only qits-projects speaks. The public identity is `(projectId, repoName)`, and both
doors carry it — the intake payload and the `BuildSuccessful` payload each gained a **nullable**
`projectId` and `repoName`.

**`applicationName = repoName != null ? repoName : repoId`, resolved ONCE in `announce` and threaded
by value from there.** That string is the image PATH `qits/<name>`, the wire alias, the
container name, the provisioned database and role, the `ApplicationKeys` key and the GC pin. Every
pipeline yml pushes its image as a literal `qits/<name>`, so an application named after a storage
UUID is every deployment ending `IMAGE_MISSING` **and** the orchestrator's collector deleting the
images that are live. `RepositoryRef` is the pair travelling together; below `announce` nothing takes
a repository id where a name is meant, and `deploy` is the one method holding both — the reference
for the spec read, the name for everything else.

**The fallback is compatibility, not a guess.** Before the rollback the storage id WAS the name, so
an announcement carrying neither field derives every identifier from the id, byte for byte what it
always did. `PdRegistrationTest` holds both arms.

**Where the id genuinely stays the id**: the spec source's address (below), and `BuildTips`' tip map,
which is keyed by `repoId` because the storage id is the repository's stable reference. Its
deployment-row **floor** follows the name — `pd_deployment` records an application name and knows no
repository id at all, so asking the rows by an id finds none and loses the cross-restart floor.

**No schema change was needed and none should be added.** `pd_deployment` and `pd_resource` have
always stored the application NAME and never a repository id (the no-FK, no-foreign-identity stance
in *Adding a dependency on another context*), so `applicationName`-as-name is the columns doing what
they already said.

### The spec is read at the RELEASED TAG, and the answer carries the commit (2026-09-03)

**`SpecSource.read` takes a git REV and returns a `SpecRead`, which is the spec plus the commit that
rev resolved to.** The ordinary caller passes `SpecSource.tagRev(version)` — `refs/tags/<version>`,
fully qualified — and the reason it is qualified is not tidiness: the git host resolves a bare
`2026.903.113443` perfectly well, and a *branch* of that name would win. The file that decides where
a container runs has to be the file the version was cut from.

**A rev is ONE path segment to the git host** (its route regex is `[^/]+` and its charset refuses a
literal `/`), so the slashes are percent-encoded: `/blob/refs%2Ftags%2F<version>/...`. jgit's
`Repository#resolve` then peels an annotated tag. A stub that reads `getRequestURI().getPath()`
instead of `getRawPath()` sees three segments and 404s everything — which is exactly what happened
to `stories/support/StoryPeers` and is why it reads the raw path now.

**The commit comes back in the `Git-Commit-Sha` header, and that is the only place a released
deployment can learn one.** qits-githost has no ref-resolution endpoint at all — no tag listing, no
ref→sha JSON — so without the header `pd_deployment.commit_sha` would be null forever and the edge
from a container back to a diff would be gone. One request, both answers. A response without the
header costs the trace edge and never the deployment.

**`GitHostSpecSource` reads one blob at two addresses.** With the name pair,
`GET <git-host-url>/git/<projectId>/<repoName>/blob/<sha>/.config/qits/deployments.yml`; without it,
the id-addressed URL it always used. Half a pair is no pair and takes the id route.
`GitHostSpecSourceTest` is plain JUnit over the JDK's own server — the two config values are
package-private fields, so no `@QuarkusTest` is involved — and it holds both arms, the 404 answer and
the 5xx refusal.

**It reads TWO blobs now, and it is one read twice rather than a second reader.** Beside the spec
sits `SpecSource.DECLARATION_PATH` — `.config/qits/configuration.yml`, what the application declares
about its own configuration — fetched at the same rev through the same address pair and returned
**unparsed**. Almost everything below the file name is shared and deliberately so: the
percent-encoded rev (the half that was got wrong once and 404'd every release-tag read), the
404-is-an-answer stance, and `statusFailure`'s retryable-versus-permanent verdict. So a declaration
the git host would not serve **holds the release `SPEC_UNREADABLE`** exactly as a spec would, and
the row's message names which of the two files did not answer.

**The name-then-id fallback is the ONE thing the declaration read does not share, and it was removed
after it held the platform (2026-09-07).** A name-addressed 404 on the declaration is
`DeclarationRead.absent()` immediately, with no second request. The fallback shipped as part of "one
read twice" and could only ever do harm here: an UNMIGRATED repository — most of them, and the whole
point of `absent()` — 404s the name route, the fallback then asks `/git/<repoId>`, and qits-githost's
storage-client guard refuses that to every caller but qits-projects. A 403 is classified retryable,
so **"this repository has no configuration.yml" arrived as a sixty-minute `SPEC_UNREADABLE` hold** —
on every unmigrated repository's deploy through the new deployer. Measured on
qits-ci@2026.907.184918; the qits-docs pilot passed only because its declaration exists and the name
read answered 200.

Two things keep the removal correct rather than merely quieter. The name comes off the release event
or the acceptance ledger and is **authoritative** — `PdRepositoryRenamedSubscriber` is what corrects
it — so there is no false miss to disbelieve, which is the argument `read` still rests on. And an
**id-only** ref is untouched: it is read id-addressed because that is `address`'s answer and not a
fallback, and a 404 there is absent for the same reason. **Do not restore the fallback on the
declaration**, and do not remove it from the SPEC read, where a name-404 usually means a real
problem — every deployable carries a `deployments.yml`.

**Nothing on this side parses the declaration, and that is the seam rather than an omission.**
qits-configuration owns the grammar; a parser here would be a second opinion about somebody else's
document, and the two would disagree on the day that grammar grows a key — which is the day a
deployment refuses a file the store takes perfectly well. There is therefore no permanent-failure
arm on the declaration read at all: every failure it can raise is a failure of the hop.

**The second read is only made when the FIRST one declared something.** A release that carries no
`deployments.yml` is a published image that asked for no deployment, and is recorded and stopped a
few lines later; fetching a declaration for it would be a second request on the deploy worker for a
version nothing will deploy. And a repository that carries the spec and no declaration is the
ordinary case for a long while — every repository predates the file — so that costs **one** 404 on
the name route and it is an answer, not a hold.

**`qits.deployments.git-host-url` shipped a WRONG default for several releases**
(`http://qits-platform-artifacts:8080/artifacts`, the address the byte plane answered on before the
git host was split out of it). It is `http://qits-githost:8080` now, the sibling qits-ci's spelling.
Every deployment overrides the key, which is why it cost nothing and hid this long — and it is why
the shipped value carries no environment prefix: a prefixed hostname is deployment config.

### …unless the file says otherwise: `application:` (2026-08-30)

**`application:` in `.config/qits/deployments.yml` decouples the deployed identity from the
repository name, and it is the whole of the wrapper reorganisation's phase 2 for this component.**
Absent — which is every file that exists today — behaviour is byte-identical to the section above:
the application name is the repository's name. Present, that string IS the application name
everywhere the repository's would have been, and the list is the same one: the catalogue key, the
swarm service and its wire alias, the container name, the image `qits/<application>:<version>`, the
provisioned database and role, the derived `host` label, the extras family
`qits.deployments.extras.<application>.*`, the `QITS_APPLICATION` the container boots with,
and the name every `Deployment*` event carries.

It exists so a repository can be **renamed** with nothing on the platform moving: `qits-ci` becomes
the repository `qits-ci-service`, writes `application: qits-ci`, and the running platform does not
notice. The image reference following the application rather than the repository is the feature — a
renamed repository's pipeline yml keeps pushing `qits/qits-ci`, and the deployer keeps pulling it.
So a repository that pins `application:` is **immune to a rename for identity purposes**, while one
that does not would change application identity on its very next release after a rename — a fresh
service, alias, database and set of rows beside the ones still serving — which makes pinning the
line worth writing before the rename rather than after it.

Five things about it, each easy to undo by accident:

- **The substitution is `DeployService.deploy`'s, and there is exactly one of it.** The parser reads
  and validates the value and no more — it never knows which repository it is reading for, the
  `ResourceProvisioning.resolve` and `browserHost` arrangement applied to the value those two are
  themselves derived from. `announce` still settles the REPOSITORY's name (id vs the name pair);
  `deploy` settles the APPLICATION's, one line after the spec read, and everything below takes it by
  value exactly as before. A second substitution point is the regression to watch for.
- **The spec read still addresses the REPOSITORY.** `SpecSource.read` takes the `RepositoryRef`,
  which is unchanged and must stay so: the blob lives at `/git/<projectId>/<repoName>`, and an
  override that redirected the read would fetch somebody else's file.
- **An unreadable spec keeps the repository's own name**, so the `FAILED` rows go to the places
  registered under it — which for a repository that has been renamed AND overrides is no place at
  all. That is the honest answer rather than a gap: nothing knows which application a file it could
  not read was speaking for, and guessing would record a failure against a stranger.
- **Two repositories declaring one application name is LAST-WINS, and this component cannot refuse
  it.** `pd_service`, `pd_deployment` and `pd_resource` record an application NAME and no repository
  identity at all — V1's header states that as the rule — so there is nothing to compare a second
  claimant against. It is not a hazard the key introduces: two repositories in two projects may
  already carry one name and already collapse the same way. What the key adds is that every
  deployment taking an override logs which repository claimed which application, so the claim is on
  the record. **Refusing it is a follow-up with a price**: a repository identity on `pd_service` and
  a migration to hold it, which is a decision about foreign identity in this schema rather than a
  detail of this key — and it would have to key on the storage id, because the repository NAME is
  the thing phase 2 changes.
- **CHANGING the value later is a decommission and a new application.** The old name keeps its
  service, alias, database, rows and routes; the new one gets fresh ones and deploys beside it. The
  rename runbook moves repositories, never applications. Nothing here helped for a while, and three
  abandoned names carried a red `FAILED` row apiece for a fortnight because of it — there is a door
  now, `POST /applications/{applicationId}/decommission`, and its section is above.

#### `renamed_from:` — the one thing an application rename may move (2026-09-26)

**The bullet above says changing `application:` is a decommission and a new application. That half
stands.** A swarm service's name IS its address and swarm cannot rename one; `pd_service`,
`pd_deployment` and `pd_resource` are keyed by an application NAME and hold no repository identity.
So a renamed application deploys BESIDE its predecessor, gets a fresh service, alias, rows and
routes, and an operator retires the predecessor by hand. Nothing here changes that and nothing
should try to.

**What was wrong was "nothing here helped".** Six applications had to drop the retired
`qits-platform-` prefix (qits-111), each successor declares the predecessor's database in
`resources:` because abandoning the data is not on the table, and each one was then refused:
`the database qits_platform_mirror is already provisioned for qits-platform-mirror`. **That refusal
is right and it stays** — the alternative is provisioning a second application an empty database and
starting it green — and retiring the predecessor first is not available either (`decommission` is a
409 while it is deployed, `scale 0` leaves it `SCALED_TO_ZERO` which still counts as deployed, and
`DELETE /services/{name}` is 403 for a workspace credential).

`renamed_from: <application>` in `.config/qits/deployments.yml` is the missing sentence — the
seventeenth key, snake_case like the majority, validated with `PdIdentifiers.requireName` because it
is COMPARED against a stored application name. Five things about it, each easy to undo by accident:

- **It moves ONE column of ONE row**: the conflicting `pd_resource` claim's `application_name`, and
  only when that claim belongs to the exact application the file named. Every other mismatch throws
  the same sentence it always did — `renamed_from` narrows the check, it does not weaken it, and a
  null value (every file that exists today) reaches the throw unchanged.
- **The transfer is worth more than getting past the refusal, and that is the part to protect.** The
  claim row is the single authority for the provisioned credential and is read one statement later by
  `findOne(applicationName, …)` to decide whether to send the stored password or a fresh one.
  Transferred *inside the same bracket and before that read*, the successor is provisioned with the
  password the role already has — so the predecessor, still running on open pools against that role,
  keeps working. Moved later, or moved without the password, the reconcile arm rotates the role and
  the rename takes the predecessor down as a side effect of its successor's first deployment.
- **It is a declaration and never an inference.** A prefix rule, a similarity test or "the previous
  holder of this database" are all "hand this application somebody else's store" with a heuristic in
  front of it. A key is written in a reviewed commit and read at the released tag, like every other
  statement in that file.
- **The idp-client arm deliberately does NOT take one.** A client id is
  `PdNetworks.alias(environment, application)` — derived, never declared — so a rename produces a
  different id, `listByClientId` finds nothing, and the check cannot fire for a rename at all. A
  transfer arm there would be unreachable code on the one check whose whole purpose is to be
  unreachable (it fires only on a derivation bug). The cost is one orphan client plus its row, and a
  fresh secret for the successor: a secret is not data, and the predecessor keeps the one it runs on.
- **`uq_pd_resource` is `(application_name, environment_name, resource_name)`**, so a successor that
  already holds a resource of that name in that tier has nowhere for the claim to move to. That is a
  refusal naming both rows, not a merge: two rows both claiming to be this application's is two
  histories, and which credential is live is not answerable from the rows.

**What a rename still needs and this key does not give it:** the release pipeline must push
`qits/<new application>` (the image ref follows the application, so a stale pipeline yml is
`IMAGE_MISSING`), and the two `qits-platform-idp` constants here —
`ResourceProvisioning.IDP_APPLICATION` (the idp URL handed to every provisioned container) and
`HttpIdpClientProvisioner.IDP_APPLICATION` (this component's own dial to the service-client API) —
must move in the release AFTER the idp successor is serving, never before.

**The release door was unaffected**, and that was checked rather than assumed at the time:
qits-workspaces' `DeploymentSpecReader` **statted** this file and never opened it (a file means
"it deploys"), so a new key was invisible to it. That door is retired — releasing is a release
request in qits-projects — but the reading it did survives here: the presence of this file is still
what decides whether `main` is finalized at release or only after a successful deployment.
qits-ci's strict unknown-key parser only ever sees `ci-event-*.yml`. And,
like every spec key: **it must ship in the deployer before any repository writes the line**, since a
spec is read at the released tag and an unknown key fails a deployment.

### `navigation-entries` claims a (slot, label) pair, not a slot (2026-08-31)

**One application contributes SEVERAL rows to one heading, and the parser refused that outright for
a release.** `DeploymentSpecParser.navigationEntries` keyed its duplicate check on the slot alone,
so `navigation-entries: project.detail.Workspaces:1, project.detail.Editor:2=editor` — qits-workspaces
asking for the workspace list and the web editor under the project node, from one application in one
container — came back as "claims the slot `project.detail` twice" and the deployment failed with
`deployment spec unreadable` while its build stayed green. A list of placements exists precisely so
an application can appear in more than one place; the slot was never the thing being claimed.

The key is the **pair**. Three things about it:

- **The same `(slot, label)` twice is still an error**, and the message names the pair
  (``claims `project.detail.Editor` twice``) rather than the slot, so a file with four entries under
  one heading says which one is the duplicate. It is one row asked for twice and no shell can draw
  two of it.
- **A repeated POSITION is NOT an error, and that is a decision.** `NavigationEntry`'s own javadoc
  has always said a repeated number is an ordinary tie the consumer breaks by label, and both
  consumers do exactly that today — qits-edge sorts slot, position, label, application, and the
  shell's jslib re-sorts `position - position || label.localeCompare(label)`. Two applications at
  one number in one slot already render as two stably-ordered rows; refusing the same tie inside one
  application would be a rule the navigation document does not have, and the rows would render
  anyway if it did.
- **qits-edge widened the same rule in the same campaign** — `EdgeRoutes.validateSnapshot` and the
  projection's primary key, which was `(environment, application, slot)`. Move one, move both: the
  edge is a hop later, so a parser alone would have turned a refused spec into a refused frame.

### The cause rides the seam, because the scope cannot (2026-08-10)

**`announce` takes a fifth value now, `causationId`, and the domain modules hold the eventstream jar
for the causation persistence trio.** `CausedRow`, `CausationStamp` and `@Uncaused` are three
jakarta-persistence-shaped types with no publish, no subscribe and no wire in them, so the module
boundary narrows from "the bus lives in `service/`" to **"the bus's SEAMS live in `service/`"**: no
listener, no publisher, no `EventFrame`, no `QitsEventBus` and — deliberately — **no
`CausationScope`** in `environments/` or `deployments/`.

The scope is what forced the parameter. `CausationScope` is a plain ThreadLocal, and this whole
component runs a build-succeeded event on `pd-deploy-worker`: the door's scope stands on the calling
thread and is gone the instant the lambda runs elsewhere. Left to the `CausationStamp` listener,
every row this component writes would record null — measured in qits-ci on the same day, a full
trigger id beside an empty causation column. So each door reads the answer where it exists and
states it:

- `bus/PdSoftwareReleaseSubscriber` passes `frame.id()`, parsed leniently — an id that is not a UUID
  costs the trace edge and nothing else. **Causation must never be able to refuse a green build.**
- `api/PdEventController` passes `CausationScope.current()`, which `CausationServerFilter` restored
  from the caller's `X-Qits-Causation-Id`. Null is a hand-made bootstrap POST: a rootless deployment,
  which is a real answer rather than a gap.

`ServiceCatalog.upsert` has the same pair for the same reason — `upsert(Upsert)` for the REST door,
where the stamp works because nothing hops, and `upsert(Upsert, UUID)` that derived registration
calls from the worker. **A new writer on a background thread states its cause as data or it records
none**; `bus/PdCausationTest` drives `onFrame` from a scopeless thread precisely so a green
assertion can only mean the explicit set happened.

`ArchRulesTest` (qits-arch-rules, test scope) makes the decision mandatory: a new `@Entity` that
neither implements `CausedRow` nor declares `@Uncaused` fails the build naming the class. It lives
in `service/` because that is the only classpath carrying every entity of the component — both
domain jars and anything this module adds. The domain modules pay no test cost for the jar: neither
has a `@QuarkusTest`, so the eventstream persistence unit never boots there and only `service/`'s
`EmbeddedPgConfigSource` owes it a database.

### The other direction: what a deployment announces (2026-08-12)

**This component publishes now.** It consumed the bus for a release and produced nothing, so a
chain in the event log ended at `BuildSuccessful` — the container a commit ended up in was reachable
only by asking this component's API. Four events close it, one per lifecycle point, all in
`deployments-events/` and all published through `DeployAnnouncer` (`deployments/control`) by
`bus/DeployEventAnnouncer`:

| event | published from | timestamp |
| --- | --- | --- |
| `DeploymentQueued` | `DeployService.queue`, one per created row | the row's `created_at` |
| `DeploymentStarted` | after the `QUEUED`→`STARTING` transaction | taken at the transition — there is no `started_at` column |
| `DeploymentActive` | after the cutover bookkeeping, last thing in `execute` — **and from the startup sweep's adoption**, which is the only path a self-update has | the row's `finished_at` |
| `DeploymentFailed` | the single `finish` funnel, when the status is not `ACTIVE` | the row's `finished_at` |

**All four carry the VERSION now, and `commitSha` beside it.** The version is the released
coordinate — what the image is tagged with, and what a consumer joins a deployment back to a release
by; the sha is the commit that tag resolved to, and it is a trace edge that may legitimately be
null. That is a **widening of the vocabulary jar**: a consumer reading the payload gains a field and
is otherwise untouched (the canonical mapper ignores unknown keys in both directions), but anything
CONSTRUCTING one of these records takes a new positional argument. Only this repository does.
`DeployEventsTest` holds the exact payload bytes, so a change here that is not also a change there
is a cross-repo break rather than a refactor.

Six things about it, each easy to undo by accident:

- **Every announcement happens AFTER the transaction that made it true**, so a consumer that reads
  the deployment back finds what the event said. `DeploymentActive` is deliberately the last
  statement in `execute`, after the old containers are reaped: an unreachable qits-events must delay
  nothing the deployment still has to do.
- **Announcing can never change a deployment's outcome.** `DeployService` wraps every call in a
  try/catch with a WARN, and the port says an implementation must not throw. Zero implementations is
  a supported configuration — `Instance<DeployAnnouncer>`, so a build without the bus deploys
  exactly as before.
- **The cause is data, and it does not need parsing here.** `PdDeployment.causationId` is a `uuid`
  column, set explicitly at queue time (see "The cause rides the seam"), and the announcer hands it
  to `publish(event, parent)`. qits-ci needs a defensive parse because its trigger id is a
  `varchar`; the leniency here already happened one layer up, in the subscriber.
- **`@ActivateRequestContext` is on every announcer method**, for the reason `ScmEventAnnouncer`
  carries it: `pd-deploy-worker` is a bare daemon thread with no request context, and the outbox
  needs one to open its transaction in. A `@QuarkusTest` driving the REST door has a context already
  and would not catch its absence — `PdDeployPublishTest` drives the worker.
- **`DeploymentObserver`'s later corrections announce nothing.** They restate an outcome minutes or
  hours later, and a consumer would first need to know that the second statement supersedes the
  first. That is a second design and it is not this one. So is `recordRejection`, which writes a
  `FAILED` row outside the `finish` funnel.
- **The startup sweep's ADOPTION does announce, and this entry is a reversal.** It used to be in the
  line above, and that was right while `DeploymentActive` was only a statement for a person to read.
  It stopped being right when the event grew `endpoints`: the platform edge projects its whole route
  table from that snapshot, so an adopted deployment that says nothing is an application the edge
  never learns a route for. **This component is that application, every single time** — its own
  self-update is `HANDED_OFF` to the orchestrator, `execute` returns before the cutover that
  announces, and the surviving instance's sweep is where it goes `ACTIVE`. It cost the deployer its
  own `/deployments` route and its navigation entry on every platform: the old UI was
  reachable by nobody and missing from the menu, while every other application announced normally.
  An adoption is not a correction — it is the **first** statement anybody makes about a deployment
  that went live. `PdSweepAdoptionPublishTest` holds it.

  Two rules inside it. **The snapshot comes off the ROW and this announcement reaches no peer** —
  V3's routing columns, below — because it runs at BOOT, and the boot in question is the one right
  after a cutover. And **a snapshot that cannot be established announces NOTHING** — a tier that is
  gone, a row that recorded no routing — because a consumer replaces rather than merges, so an empty
  endpoint list would *delete* the routes it could not describe. A repository that genuinely declares
  none is a different thing and does announce its empty snapshot.

The vocabulary jar is `qits-platform-deployments-events`. It depends on `qits-eventstream` and
nothing else — see the partition above for why it is a module.

### A TIER announces too, and it is a second port (2026-09-26, qits-387)

**Four events described a deployment's lifecycle and nothing described an ENVIRONMENT's.** qits-edge
learned its tier list from static configuration (`qits.edge.environments`), which drifts the moment
anybody creates or renames one, and the rows live here. So `EnvironmentCreated`, `EnvironmentChanged`
and `EnvironmentDeleted` join the vocabulary jar, published through `EnvironmentAnnouncer`
(`deployments/control`) by `bus/EnvironmentEventAnnouncer`, from the three operations in
`EnvironmentOperations`.

**Deriving the set from `DeploymentActive` was considered and REJECTED.** That event says "a change
is live", not "this tier exists": a tier created this morning would be invisible until something
deployed into it — which may be never — and a tier deleted or renamed would go on existing in every
consumer's projection for ever. The set of environments is a lifecycle fact and needs lifecycle
events.

Six things about it, each easy to undo by accident:

- **`environmentId` is the identity on all three, and that is the decision that matters.**
  `PdEnvironment.id` is assigned at creation and never changes; `name` is mutable, because
  `PATCH /deployments/api/environments/{id}` renames a tier. A consumer keyed by id handles a rename
  as an in-place update of one row; one keyed by name would carry the old name for ever, with no
  `EnvironmentDeleted` ever coming for a tier that was renamed rather than deleted.
- **There is no `previousName` and there must not be.** It is the repair for the name-keyed
  projection the stable id exists to make unnecessary, and carrying it would invite exactly that
  design.
- **The tier's name is spelled `environmentName` and CANNOT be `name`.** `QitsEvent` declares
  `name()` as the event's own label, so a record component called `name` overrides it — the log would
  show `preprod` where it should show `EnvironmentCreated`, and because the interface's four methods
  are excluded from the canonical payload, the tier's name would be stripped out of the payload
  entirely. Neither is a compile error; `EnvironmentEventsTest` is what catches it. It is also the
  spelling the four `Deployment*` events already use.
- **A second PORT rather than three more methods on `DeployAnnouncer`**, and it takes **no `cause`
  argument**. Those four are one statement made four times about one deployment, on
  `pd-deploy-worker`, behind a queue hop where `CausationScope` is empty and the cause has to travel
  as data. These three are about the tier, made on the request thread of the environment door with
  the filter's restored scope still standing — so `publish(event)` reads the ambient cause itself.
- **Every announcement happens AFTER the transaction that made it true**, and none of them can change
  the operation's outcome: `EnvironmentOperations.announce` wraps each call in a try/catch with a
  WARN, `Instance<EnvironmentAnnouncer>` so zero implementations is supported, exactly as
  `DeployService.announce` does. A create announces **between** the row and the bundle network, not
  after both: the tier exists when the row commits, and a driver call that threw would otherwise
  leave a committed tier nobody is ever told about.
- **The tier that LOSES the designation announces nothing**, and `pd_environment` gains no lifecycle
  column — the deletion stays a hard row delete and the tombstone is the consumer's projection's,
  where qits-projects puts it too. A consumer that tracks the flag reads `designated: true` as "this
  one, and therefore no other", which is safe because at most one row is ever true.

**`bus/PdEventWireRegistrationTest` is new with them and is the assertion `EventWireReflection` never
had**: every `QitsEvent` on the events jar must be in that annotation, as a closure check rather than
a count. Unregistered, a record publishes an **empty payload** in the native binary and `publish`
does not fail — a green JVM suite, and a consumer reading a frame of the right name with no fields in
it.


## Adding a dependency on another context

Don't. This component depends on three published qits jars — `qits-auth-core`, `qits-eventstream`
and `qits-arch-rules` (test scope) — and all three are **platform libraries rather than contexts**:
shared machinery, no domain, no entity of anyone else's. It has no dependency on another *context*
and should not grow one. Things arrive as an HTTP payload on the intake, as an event on the bus, as a URL in config, or
not at all. Never add a JPA relation to another context's entity.

**The bus does not change that, and the subscriber is written to keep it true.** qits-ci's
`SoftwareRelease` reaches this component as five strings decoded from a payload, against a signature
spelled as the literal `"SoftwareRelease"` — there is no dependency on `qits-ci-events` and there
must not be one. The cost is that a rename over there is silent here, which is the cost the intake
path already carries.

The rule reaches **inside this schema** too: `pd_deployment` names its service and its tier as plain
`String` columns with **no FK**, even though the topology is two tables away. Deployment history
outlives the rows that described it, and the rollback pins read off it must keep answering whatever
the catalogue says today.

## Schema changes

`environments/src/main/resources/db/platformdeployments/migration/`, hand-written, its own lineage on
its own datasource — keep appending, never edit an applied migration. It lives in `environments/`
because the component is one database; the module split is code, not storage.

**`V2__causation.sql` is that rule being followed, and it is also what "one lineage" looks like in
practice**: three `causation_id uuid` columns across two modules' tables in one file, because
`pd_deployment` belongs to `deployments/` and its `create table` is already here. Nullable, no
backfill, no index, and never a foreign key — the event it names lives in qits-events' store. The
per-entity decisions, each argued in the entity's own javadoc and enforced by `ArchRulesTest`:

| entity | decision | where the cause comes from |
| --- | --- | --- |
| `PdDeployment` | `CausedRow` | set explicitly in `DeployService.queue`/`recordRejection` — the whole feature, and the worker hop is why it is not stamped |
| `PdEnvironment` | `CausedRow` | the stamp, from the REST filter's restored scope; a tier is created on the request thread with no hop |
| `PdService` | `CausedRow` | explicit on the derived path, stamped on the operator's `PUT`. Created once and updated in place, which is what insert-only stamping is for |
| `PdServiceLink` | `@Uncaused` | none. Every upsert deletes and re-inserts the row, so the column would record the last rewrite rather than a cause |
| `PdResource` | `@Uncaused` | none. A converging registry entry rather than a record of an occurrence, and its other writer is boot self-registration with no event behind it |

**`V3__deployment_routing_snapshot.sql` is the one place a row holds a SPEC value**, and V1's header
says the spec's fields deliberately do not — so read its own header before adding a second. The rule
it bends was written for values whose only reader is the `execute` call that fetched them; routing
grew a reader that outlives the process, because a **self-update** is announced by the successor's
startup sweep, which has the row and no live process that read the file. Storing it is what keeps
that announcement off the network at boot. `upstream_port` is the **sentinel**: the spec always
resolves a port, so null means "queued before these columns" and never "no port", and `routes` being
null or empty is the ordinary answer of an application with no public routes.

**The store is PostgreSQL, and the lineage restarted at V1 to say so.** The H2 lineage (V1 + V2) was
deleted rather than continued, and that was a decision with one precondition: the migration onto
postgres is an **unwrap and a re-bootstrap**, so no database anywhere is on the H2 lineage and no
`V3__move_to_postgres.sql` had a reader. What the fresh V1 is, is the two H2 migrations translated —
identity columns instead of `auto_increment`, `text` instead of `clob`, V2's `platform` flag folded
into the table it belongs to with **no backfill**, since every database reaching it is empty and the
bootstrap creates the tier with the flag already set. Two parity notes are written into its header
because postgres would now permit what H2 could not, and the answers are still the code's: no check
constraint on any enum column, and no partial unique index on `pd_environment.platform`. **A second
clean start is not a precedent** — it cost a re-bootstrap, and the ordinary rule (append, never edit)
is back from V1 onward.

**`V6__deployment_request.sql` adds the decision in front of the queue** — see *The event side* for
what a request is and why it is not a column on `pd_deployment`. It points AT the deployment
(`deployment_id`, nullable) rather than the other way round, because the request is the cause and is
written first.

**`V7__deployment_version.sql` is the coordinate change, and its whole care is the two nulls.** A
deployment is a released version now, so `pd_deployment.version` holds the CalVer stamp — the tag
the image carries, the rev the spec is read at, and what the startup sweep compares a running image
against. `commit_sha` survives, is **nullable**, and means what it says: the commit the released tag
RESOLVED to, read out of the git host's `Git-Commit-Sha` header. Null there is a real answer (a
repository with no `deployments.yml` 404s the blob read, which says nothing about where the tag
points).

**There is NO backfill and there must not be one.** A row written before V7 describes a deployment
whose image really is tagged with a sha, so copying that sha into `version` would make the rollback
pins claim a tag that does not exist. Every reader goes through **`PdDeployment.imageTag()`** — the
single spelling of "what tag is this deployment's image", version first and commit_sha behind it —
and three of them have to agree: the pull, the sweep's adoption check, and `RollbackPins`. Adding a
fourth reader that touches `version` directly is the regression.

**The pin wire field is still called `shas` and must stay so.** qits-artifacts' image collector
parses `{"pins":[{"applicationName","shas"}]}` and matches each string against an OCI tag by plain
equality, so it followed the version change with no edit at all — and it is **fail-closed**, so a
renamed field would abort garbage collection across the platform rather than degrade it. The values
inside changed; the contract did not.

**`V8__platform_plane_is_the_main_environment.sql` gives `pd_deployment` the PLANE as a column,
backfills it, moves the plane's rows onto the designated tier, and drops `pd_environment.branch`** —
see *…and the PLATFORM PLANE lands there too* for what changed above the file and why the backfill
is the decidable kind. Three things worth carrying:

- **`deployment_target` was not null and had no default**, `pd_service.deployment_target`'s rule
  applied to the execution row: every writer states the plane, and a row that could not say would be
  the inference the file existed to remove. **That rule is what forced V13 to DROP the column rather
  than leave it behind** — the writer is deleted, so an orphan `not null` column with no default
  would fail every INSERT of a deployment row.
- **The backfill reads exactly one statement**: `environment_id is null` meant the platform plane,
  by V1's header, so it becomes `PLATFORM` plus the designated tier. On an install with no
  designation the subselect answers null and nothing moves — which is every database the suite
  migrates.
- **`pd_resource` moves with it**, and the stale per-tier row it would collide with is deleted
  first. The registry key is what `ResourceProvisioning` looks a credential up by before every
  deploy; a miss takes the reconcile arm and rotates a password a running container is using.

The suites run every migration against an **empty** schema, so a backfill is untested by them.
`deployments/src/test/.../PdSchemaTest` is the shape to copy: plain JUnit, a real postgres from
`EmbeddedPg`, Flyway, then the claims. A migration that backfills needs a test that migrates to the
version before, writes the rows the old code wrote, and migrates the rest of the way —
`Flyway.configure().target("<version>")` is how it stops halfway. (V2's backfill test was that, and
it went with V2.)

Write inserts in those tests with **named columns**. A positional one makes every later migration a
change to a test that had nothing to do with it — the H2 lineage's V2 demonstrated it by adding one
column and breaking every positional insert in the suite.

Deployment listings order by `seq`, V1's identity column, and **not** by `createdAt desc, id desc`:
the id is a random UUID, so that tiebreak swapped two rows recorded in the same tick at random —
which is what the deployments of one build-succeeded event are, and what a client reads "the current
one per application" off.

**`V13__the_platform_plane_is_deleted.sql` is the lineage's second backfill, and it CONVERTS before
it drops** — see *The platform plane is DELETED* for the whole argument. Three things worth carrying:

- **The insert comes first and the drops come last**, because the insert reads the column. Each
  `pd_service` row saying `PLATFORM` is linked into `pd_environment.platform` — the tier the plane had
  deployed into since V8, which is why the translation is decidable rather than a guess — and only
  then do `deployment_target` and its two indexes go, from `pd_service` and `pd_deployment` alike.
- **`not exists` rather than `on conflict`.** `uq_pd_service_link` is `(service_id, environment_id)`,
  and a service already linked into the designated tier is an environment service that needs nothing,
  not a collision to swallow.
- **An install with no designation is left alone**: the cross join answers no rows, so nothing is
  linked and the columns still go. That is every database the suite migrates, which is why
  `PdSchemaTest` pins it rather than assuming it — a fresh install must not end up with a link to
  nowhere.

**Nulls are distinct to `=`, and the queries still test for one.** A deployment's `environment_id` was
null until V8 and is the designated tier now, so the null-testing arms of
`listActiveByApplication`/`listByApplication` are there for rows written before it on an install
that had designated nothing. Keep them: `= ?` matches nothing against a null, and the startup
sweep's adoption hangs on it — get it wrong and a self-updating instance comes back having failed
its own deployment while a second row still claims to be ACTIVE. **What must not come back is a
query that reads the null as a PLANE**; there is no plane, and V13 dropped the column that was the
last place the question could be asked.

## Dependencies

**The client is the only submodule.** `service/src/main/webui` is
qits-deployments-platform-frontend; `git submodule update --init` is half of a clone here, and both
pipelines (`.config/qits/ci-event-release-request.yml`, `.config/qits/ci-event-release.yml`) run it
for that reason. Shared auth comes from the platform
Maven repository as `qits-auth-core`, and the event bus client as `qits-eventstream` — ordinary
Maven dependencies, never gitlinks.

**Both are version-pinned by a property in the root pom, one line each**, because a release train
step rewrites exactly that element: `.config/qits/ci-event-upstream-auth-core.yml` and
`.config/qits/ci-event-upstream-eventstream.yml` each `sed` one `<…version>` and force-push the
result onto a maintenance branch. A second spelling of either version anywhere would be left
behind. A bump lands on a branch and not on main on purpose: a library release is not a decision to
redeploy the deployer.

**`qits-eventstream` sits in all four modules now**, and only `service/` uses it for the bus: the
domain modules take it for the causation persistence trio and `deployments-events` for `QitsEvent`
itself, which is a narrowing of the boundary rather than a hole in it — see "The cause rides the
seam". `deployments/` additionally holds `qits-platform-deployments-events`, because
`DeployAnnouncer` is spelled in those records; they are plain data with no publish and no transport
in them, so that is the same narrowing rather than the bus reaching into a domain module. `qits-arch-rules` is a third published
jar, test scope, in `service/` only; it is version-pinned by a property of its own and no release
train step rewrites it.

**`qits-eventstream` brings two extensions new to this deployable** — `quarkus-scheduler` (the
outbox and catch-up sweeps) and `quarkus-websockets-next` (the stream client, which registers no
route, so `quarkus.quinoa.ignored-path-prefixes` is unchanged) — and one **mandatory deployment
resource**, below.

**`quarkus-oidc-client` is back and it is one peer's**, `service/` only: the bearer
`confighost/IdpExtrasBearer` presents to qits-configuration. It is the other direction from
`quarkus-oidc`, which validates what arrives. It ships disabled in both spellings — the named client
and the default one — so a clone-alone build needs no idp, reaches no network and holds no secret;
see the extras section above for the switch and the deployment-side family.

**`quarkus-undertow` must never be on the classpath.** Its presence breaks Quinoa's production static
serving — the client 404s from a build that was green — and it arrives *transitively* from anything
servlet-shaped:

    ./mvnw -pl service -am dependency:tree | grep -i undertow

**Quinoa is in no BOM**, so its version is pinned by hand in the root pom's properties. 2.8.2 is the
last release built against a Quarkus *older* than the platform's 3.34.6; 2.8.3 is built against
3.36.2, ahead of us. Bump only when the platform's Quarkus passes the version a release is built
against.

## Tests

- App-level config lives in `service/src/main/resources/application.properties` and Quarkus merges it
  into the test config. **Never re-declare an app-level setting in test resources** — the test copy
  carries only the port, the persistence-unit wiring and `quarkus.devservices.enabled=false`.
- **No dev services and no containers, ever.** A dev service is a container start, and the first rule
  here is that a clone tests green with no docker. `quarkus-oidc` in particular launches a real
  Keycloak the moment a profile leaves `quarkus.oidc.auth-server-url` unset — measured on the
  ancestors, not feared. The store being postgres does not change that answer: `testdb/EmbeddedPg`
  starts **zonky's** postgres — real binaries resolved as Maven artifacts, spawned as a child
  process — and `testdb/EmbeddedPgConfigSource` hands its url, username and password to every
  `@QuarkusTest` at an ordinal above `application.properties`, because the port is chosen at run
  time and cannot be written down. Testcontainers is not on this classpath and must not arrive.
  `EmbeddedPg` is **copied** into `deployments/` rather than shared: a test-jar dependency between
  two modules that have none is the higher price.
- **That config source hands out SIX values, not three, and the second three are easy to think
  unnecessary.** The bus is dark in `%test`, and **dark is not absent**: `qits.eventstream.enabled=false`
  stops dialling, sweeping and claiming, not the datasource — Quarkus opens the connection and runs
  Flyway at boot regardless. So the `eventstream` store gets a database of its own on the same
  embedded instance, or the suite does not start. Only `clean-at-start` is written in the test
  properties file; the locations and the persistence-unit wiring ship in the jar and a copy here
  would drift.
- **The bus darkness itself is asserted**, in `bus/PdEventstreamDarknessTest`, for the reason the
  OTel keys are: the way a missing switch fails is not a failure but a suite that redials an
  unresolvable host every thirty seconds and reads as slow. It also asserts the subscriber survives
  ArC's unused-bean removal — nothing injects it by name, and a removed listener consumes nothing
  and says nothing to admit it.
- **The bus tests drive `onFrame` directly, not a stub qits-events.** What belongs here is this
  component's half — the decode, the tip check, the call into the seam. The funnel, the claim ledger
  and the catch-up sweep are the library's and are proved in its own repository; a stub here would
  re-prove them and prove nothing about a deployment.
- **The PUBLISHING test aims the bus at a closed port**, which is the same stance from the other
  side: `PdDeployPublishTest` turns the bus on in its own `@TestProfile` (`qits.events.url` is
  `http://localhost:1`, the scheduler and the startup catch-up are off), so every publish lands as
  exactly one `outbox_event` row with the canonical payload it would have been sent with. A row IS
  the publish from this side of the bus. It reads them through the `eventstream` persistence unit's
  own `EntityManager`, never a Panache static — `OutboxEvent` comes from a jar this application did
  not compile, and the static throws naming the wrong problem.
- **Machine-token tests mint their own tokens.** `MachineTokens` signs RS256 with the key pair in
  `service/src/test/resources/machine-token-*.pem`, and `MachineGuardEnforcedProfile` hands
  quarkus-oidc the public half, so the enforced path is exercised end to end with no
  qits-platform-idp to reach. Those PEMs are **test fixtures, not credentials**.
  **A minted token carries `groups`**, because the idp's does and because `@RolesAllowed` reads
  nothing else — a fixture that mints only `iss`/`sub`/`aud` produces a caller that authenticates
  and is then refused 403 on every guarded call. `MachineTokens.rolelessToken` mints exactly that,
  on purpose, to assert the refusal.
- `FakeDeploymentDriver` and `FakeSpecSource` are `@Mock` and application-scoped, so they are shared
  across tests: reset both in `@BeforeEach`, use distinct **environment names, repository ids and
  service names** per test, and read their state through their **methods** — the injected reference
  is a CDI client proxy, and a field read on a proxy sees the proxy's fields, not the bean's. The
  suite shares one embedded database across classes (Flyway cleans at start, not between tests), and
  a **platform** service registered by one class shows up in every other class's link query — so
  assert with `hasItem`, never with a size.
- They live in `…deployments.control`, the seam's own package, which is also what lets
  `PdSweepAdoptionTest` drive the package-private `sweepInFlight()`.
- Flow tests poll the read surface to a deadline rather than reaching into the service — the same way
  a caller experiences the API, and immune to the worker's timing. Platform deployments used to be
  the one thing that surface could not show, then they could be asked for two ways at once
  (`?environmentId=<tier>` and `?environmentId=platform`); there is one tier listing now and the
  second spelling 404s. `awaitApplied` (wait on the driver) survives because it is still the shortest
  way to synchronise on a deployment the worker has finished, not because the listing is blind.
- `OpenApiSchemaExportTest` writes `docs/openapi.yml`. Regenerate and commit when the surface
  changes: `./mvnw -pl service -am test -Dtest=OpenApiSchemaExportTest
  -Dsurefire.failIfNoSpecifiedTests=false`. The intake is `@Operation(hidden = true)` (a wire API);
  everything else is the document. The test classpath is indexed too, so a new `@Path` resource under
  `src/test` lands in the committed document unless it is hidden.
- `PdPackagedSurfaceIT` runs the **packaged artifact** (fast-jar under `-DskipITs=false`, binary
  under `-Dnative`) and asserts what a native build can silently lose: the build-time route prefixes,
  the shipped datasource *expressions* — **both** of them now, since it hands the launched process
  `QITS_RESOURCE_DB_*` and `QITS_RESOURCE_EVENTSTREAM_*`, the generic contract a deployment
  supplies, rather than restating the datasource keys, so the jars' own `${…}` indirection is what
  is under test and a packaged artifact missing either triple fails here rather than in a
  deployment — Flyway's migration surviving as
  a resource, and — the claim the ancestors could not make —
  both domains round-tripping in one process against one database. Its embedded postgres reaches
  the profile through a **system property**, because a `QuarkusTestProfile` is instantiated in two
  classloaders and a static field is not shared between them. It points
  `qits.deployments.container-runtime` at a binary that does not exist, which keeps it
  free of host side effects and proves every driver call degrades to a warning rather than a
  failure.
- **`PdPackagedSurfaceIT` is also the only test that ever sees the client.** Quinoa is disabled in
  test mode, so no `@QuarkusTest` here has a client at all — a unit test asserting anything about the
  segment would pass against a process serving nothing.
- **`TokenValidationBootstrapIT` is the second packaged IT and the first class of the userflow
  catalogue.** It opens the gate — `qits.auth.machine.required=true`, which is what turns the shipped
  `quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}` on — and points
  `quarkus.oidc.auth-server-url` at `MockIdp`. That is the half of the shipped OIDC block no other
  test reaches: `MachineGuardEnforcedTest` opens the same gate but **inlines the verification key and
  clears `auth-server-url`**, precisely so it needs no idp — so the boot-time JWKS fetch,
  `discovery-enabled=false` with `jwks-path=jwks` joined onto the URL, and `connection-delay` are
  exercised here or nowhere. Both its stories drive `GET /deployments/api/pins`, the one
  guarded read whose caller is a machine (`qits:system`, qits-platform-artifacts' image
  collector) and which reads nothing but deployment rows.

### The userflow catalogue

Fourteen `@UserStory` methods in five classes, so `mvn verify -DskipITs=false` also emits
`service/target/userstories/` — a story log plus a mermaid **network** diagram each — which the
non-gating second step of `.config/qits/ci-event-release-request.yml` publishes, once per release
request fold, as the docs bundle `@userflows/qits-deployments`. They are **browserless** (an `Interactions` parameter and no `Flow`),
so qits-userflows-javalib's transitive Playwright never launches anything.

| class | category | what it is about |
| --- | --- | --- |
| `api.TokenValidationBootstrapIT` | `authentication` | the boot-time JWKS fetch and the three doors of the pin ledger |
| `stories.configuration.DeploymentConfigurationIT` | `configuration` | the extras read with the deployer's own credential, the refusal when it cannot be read, the declaration seeded before anything is scheduled, and the refusal when the store will not take it |
| `stories.deployment.BuildDeploymentIT` | `deployments` | a green build end to end — create, replace in place, and an image nobody published |
| `stories.operations.PlatformOverviewIT` | `operations` | what an operator reads, and the keep-set qits-platform-artifacts reads |
| `stories.refusals.AccessRefusalIT` | `refusals` | the two role sets, and the fact that they do not overlap |

Six things about how they are built, each easy to undo by accident:

- **ONE `@TestProfile` for all five** — `stories.support.StoryProfile`, which extends
  `PdPackagedSurfaceIT.PackagedUnderTarget` (one answer to "what does a launched
  qits-platform-deployments need in order to boot", one parking trick, `databaseUrl` reused rather
  than copied) and adds its own two databases plus every seam a story moves. A `@TestProfile` is what
  failsafe launches a process for, so a second profile would be a second deployer with a second
  startup whose traffic landed in whichever diagram happened to be open. **A new story class names
  this profile or it is a new process.**
- **The diagram is observed, not narrated.** `Interactions` records notes only. The near side is
  `NetworkTaps.restAssured` — the framework SHIPS the filter this repo kept a hand-written copy of
  until 2026.829, and the copy (`api/StoryNetworkFilter`) is deleted. The far sides are cumulative
  `NetworkCapture.source`s: `MockIdp`'s recording, `stories.support.StoryPeers`' access log and
  `stories.support.StorySwarm`'s call log. `NetworkCapture.actor(...)` before a call is what names an
  edge's initiator, because a bearer cannot say whether the caller is qits-ci or an impostor.
- **The orchestrator hop is EVIDENCE, not a claim.** `PdProcess` spawns the docker CLI, so
  `StorySwarm` writes a recording POSIX-sh executable and the profile points
  `qits.deployments.container-runtime` at it. It keeps enough state — services, their
  environment, and *when an update was issued*, stamped in Go's own `time.Time.String()` spelling —
  that `awaitConverged`'s `StartedAt` matching runs for real against it. Labels are **summaries**
  (`service create story-tier-story-web -> 0`), never argvs: a `service create` carries a generated
  deployment uuid and a Go `--format` full of braces, and either would move the `networkHash` or
  break the mermaid. A story that wants a FLAG asserts on `StorySwarm.argvOf(...)` instead.
- **`StoryPeers` is one stub impersonating three peers, attributed by path** — `/git/…` is
  qits-githost, `/configuration/…` is qits-configuration, `/idp/token` is qits-platform-idp. It is
  stateless: every answer is a pure function of the application name in the path, which is why
  `story-misconfigured` is refused in every run and in every order.
- **Order is load-bearing, and it is the class orderer's.** A cumulative source is attributed by a
  cursor, so pre-story traffic lands in whichever story drains first — the JWKS fetch belongs to
  `TokenValidationBootstrapIT`, and `PlatformOverviewIT` reads rows `BuildDeploymentIT` deployed.
  `@UserflowRunsAfter` states it; ties break by FQCN, which is why the packages are named so that
  alphabetical is the intended order (`…deployments.api` before
  `…deployments.stories.{configuration,deployment,operations,refusals}`). The orderer is registered as
  `junit.quarkus.orderer.secondary-orderer` in `service/src/test/resources/application.properties`:
  quarkus-junit ships its own `junit-platform.properties` and surefire hard-fails on a local one that
  overrides it.
- **Fixtures are invisible to the tap by construction.** `stories.support.StoryPlatform` creates the
  tier and polls for a settled deployment through a plain `HttpClient` — a client no RestAssured
  filter is attached to — so a diagram shows the walk somebody takes and not the fixture somebody
  built. It is called from `@BeforeEach`, because `RestAssured.port` is `-1` in `@BeforeAll`.

**Two coverage gaps, stated rather than hidden:**

- **The observation ticker is OFF in the story profile** (`observe-interval-seconds=0`).
  `DeploymentObserver` asks the orchestrator `service ps <name>` — **byte-identical** to the call a
  deployment's own convergence check makes — so a recording cannot tell a timer pass from a
  story-driven one, and an arrow that appears depending on how long a story took is a `networkHash`
  that never settles. Filtering only works when lines differ by content. So no story covers the
  observer's `FAILED`/`GONE` → `ACTIVE` recovery, its two-strike demotion or its decommissioning of a
  recovered row's predecessor; `PdDeploymentObservationTest` holds those against the fake driver and
  they stay a `@QuarkusTest`'s claim.
- **No story provisions a resource.** `ResourceProvisioning` speaks DDL to a postgres reached at
  `PdNetworks.alias(<tier>, "qits-oci-postgresql")` — derived, never configured — so a story would
  need a host by that name. The story specs declare no `resources:`, and the provisioning matrix
  stays `PgResourceProvisionerTest`'s (which runs against a real postgres for its own good reasons).
  The same goes for the self-update's `HANDED_OFF` arm and the startup sweep's adoption: both need a
  process that is replaced mid-deployment, which a story cannot stage.

- **`skipITs` stays `true` in the root pom and the userflow pipeline opts in by name**
  (`-DskipITs=false "-Dit.test=TokenValidationBootstrapIT,DeploymentConfigurationIT,BuildDeploymentIT,PlatformOverviewIT,AccessRefusalIT"`).
  That run passes `-Dquarkus.quinoa=false` — the step container's clone does not recurse, so
  `service/src/main/webui` arrives empty — and half of `PdPackagedSurfaceIT` is about the client, so
  a blanket opt-in would make it red on a test that is right. **A new story class joins that comma
  list in the commit that adds it**, or it never runs in CI.
- **Both poms that name zonky architectures carry the `-alpine` binaries as a fourth arch**
  (`deployments/`, `service/`). The CI step containers are Alpine (musl) and `verify` there is the
  whole reactor; a missing arch resolves fine and then fails mid-suite with no binary for the
  platform it is on.

## The image and the pipeline

`docker/Dockerfile` and `.config/qits/ci-event-release.yml` are two halves of one thing (the gating
step of `ci-event-release-request.yml` is the same two halves, minus the push), and the seam
between them is the only reason either is interesting: **the client cannot be built inside a docker
build.** It depends on `@qits/ui-components`, which lives only on the platform's own npm registry,
and a `RUN` step reaches the public internet but reaches that registry by no address at all. So the
pipeline step installs and builds the bundle, and the Dockerfile's builder stage neuters Quinoa's
install/ci/build commands to `--version` and packages what it was handed.

Four things follow, each load-bearing:

- **`.dockerignore` does NOT exclude the client's `dist/`.** That departs from the platform's Quinoa
  reference — here `dist/` is the payload, and excluding it fails the build at the `test -f` guard.
- **The two `package-manager-install` flags exist only on the Dockerfile's `mvnw` line**, because the
  Mandrel builder image ships no node. They must never go into `application.properties`: a local or
  CI build must use the node on `PATH`, so no build silently downloads a toolchain. `22.22.0` is the
  platform pin.
- **The bundle is `cp`'d onto itself before the build.** Quinoa *moves* `build-dir` rather than
  copying it, and overlayfs cannot rename a directory that still lives in a lower image layer — it
  answers EXDEV and the JDK's fallback refuses a non-empty directory, dying with
  `DirectoryNotEmptyException` seconds in. The `cp` re-materialises it in the layer that is about to
  move it, which is why it has to be in that same `RUN`.
- **The build runs on the PLATFORM BUILDER, and no `--network` flag exists to argue about.**
  `build: true` + buildctl replaced `docker: true` + `docker build --network host` (the wrapper's
  qits-buildkit-plan.md): a RUN executes in the builder's namespace on the platform network, so the
  maven registry rides in as a `QITS_DOMAIN` build-arg, from which `https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven`
  is derived — and the old host-networking doctrine retired with the host-daemon build.

The pipeline also rewrites `package-lock.json`'s `resolved` **origins** before `npm ci`: npm fetches
tarballs by the absolute URL in the lockfile and ignores the configured registry, and npm's own
`--replace-registry-host` is broken for a registry mounted under a path prefix. The committed
lockfile keeps the developer-host origin, which is correct locally.

**This repo is its own deployer**, and it is an ordinary service in the one tier like everything
else. Its `.config/qits/deployments.yml` still carries `deployment_target: platform`, which is read
and dropped — the key is retired and the tolerance is permanent; the line is swept by a task of its
own. A release announces this component to itself, and **under swarm** it deploys itself: the manager
arbitrates the succession, the `/deployments` surface blips mid-cutover (this repo's spec
says `update_order: stop-first`), and a successor that misses its health gate leaves the predecessor
serving.

**Its own move is the exception, and it takes a hand step — in BOTH directions.** A service name is
its address under swarm and swarm cannot rename one. The move onto the plane was
`<env>-qits-deployments` → bare `qits-deployments` and needed `docker service rm
<env>-qits-deployments` afterwards; deleting the plane moves it back, `qits-deployments` →
`dev-qits-deployments`, and needs more than that — the driver's self-update exemption compares
`ownServiceName()` against the wire alias, and after this change those two strings differ, so the
exemption does not fire and the deployer would create a second service beside itself. The exact
commands are in *Retiring the plane's bare-named services* above.

**There is no deploy ref any more.** A RELEASE deploys — see *The event side* —
and it lands in the platform's entry tier. `platform/main`, `SpecSource.DEFAULT_PLATFORM_BRANCH`,
the spec's `branch:` key and, now, `environment/<name>` as a trigger are all gone. `main` stays the
integration trunk, and a push to it still builds and ships nothing; so does a push to anything else.

**Which environment is the entry one is the same column it always was** — `pd_environment.platform`,
true on exactly one row. What it decides has narrowed to one sentence: which tier a release ENTERS
at. `DeployService.entryTiers()` is the one reader. **It STAYS under that name** — "where releases
land" survived the plane's deletion, and the rename is a later feature's.

The flag is a designation, not a link. **`EnvironmentService.designate`
moves it** — clearing the old holder and setting the new one in one transaction — because that is
where the invariant belongs. Postgres does have a partial unique index and V1 deliberately declines
it: an index would also forbid the intermediate state of the very two statements that move the flag.
Clearing the flag outright is a 409, and so is deleting the environment holding it; both would leave
the platform with nowhere for a release to land. `PdEnvironmentApiTest` holds those claims, and
`PdDeploymentFlowTest.aReleaseIsRolledOnceByTheTierItEntersAt` holds the gate.

**`deploy_branches:` is retired: accepted, validated, acted on by nobody.** Its one reader was
qits-workspaces' release flow, which pushed a release onto *every* branch the list named — a
fan-out rather than a ladder, and with three tiers it would have shipped into all three at once.
That flow is gone with the door: a release is a VERSION now, published against a tag, and where it
lands is a Deployment Request against an environment — never a branch a repository names. The
parser still tolerates the key, for the reason `deployment_target`'s own tolerance is permanent:
**a spec is fetched at the RELEASED tag**, so a redeploy of an older version still presents
a file carrying it, and an unknown key fails a deployment. Do not write it into a new file; do not
remove the tolerance.

## A red gate is not always this repository (2026-09-10, `b63968c2`)

The nightly bump of `eventstream 2026.908.70503 → 2026.910.64632` was folded into release request
`b63968c2-7113-472f-b0a4-996c7fdb0bb3` and REJECTED at 07:14: gating run
`3248b7f4-58a9-480c-9b8c-f78e3113444f` died in the maven step with

    NullPointerException: Cannot invoke "InjectionPointInfo.isProgrammaticLookup()"
      because "injectionPoint" is null
        at io.quarkus.arc.processor.UnusedBeans.findRemovableBeans(UnusedBeans.java:44)
        at io.quarkus.arc.deployment.ArcProcessor.validate(ArcProcessor.java:491)

**Nothing was wrong with the fold, and nothing was fixed to make it pass.** Written down because the
next person to see this stack will start by looking for a bean of ours, and there is none to find.

- **The bump moved no bytes.** The upstream release was pom-only — qits-eventstream's own version
  and its `qits.db-core.version` — and unpacking both jars and diffing everything outside
  `META-INF/` gives identical classes. The transitive db-core move it carries never reaches this
  build either: the root pom pins `qits-db-core` in `<dependencyManagement>` at the same
  `2026.906.171046` in both trees. The fold's effective classpath **equals `main`'s**, and `main`
  had just released green as `2026.910.61259`.
- **The fold builds.** The pipeline's own command at the rejected sha — `clean verify`,
  `-Dquarkus.quinoa=false -DskipITs=false` and the five ITs — is green, repeatedly, on a container
  of the same shape.
- **The NPE is a data race inside Arc's augmentation, not a bean graph it cannot resolve.**
  `BeanDeployment.BeanRegistrationContextImpl.syntheticInjectionPoints` (arc-processor 3.34.6) is a
  plain `ArrayList` appended from `accept(BeanInfo)`, i.e. once per synthetic bean, from the
  parallel `build-N` steps that produce them. A racing `ArrayList` append publishes the size before
  the element, so a later reader sees a **null slot**; that list is copied into the deployment's
  injection points and `UnusedBeans` then dereferences it. Timing, therefore: this run started
  fifteen seconds after the `main` release run, two augmentations deep on one box.

So the cure is a re-fold, not a commit — this note is only what made one happen. **If it recurs and
has to be forced through, `-Dio.quarkus.builder.execution.corePoolSize=1
-Dio.quarkus.builder.execution.maxPoolSize=1` serialises the build steps and the race cannot
happen.** Do not make that the default: this suite restarts Quarkus per `@TestProfile`, and it pays
the serialisation every time.
