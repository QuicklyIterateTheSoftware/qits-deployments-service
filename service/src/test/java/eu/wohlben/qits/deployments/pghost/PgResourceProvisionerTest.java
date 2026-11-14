package eu.wohlben.qits.deployments.pghost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.control.ResourceProvisioner;
import eu.wohlben.qits.deployments.testdb.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The idempotency matrix, against a real PostgreSQL — plain JUnit, no Quarkus, because the subject
 * is what postgres does.
 *
 * <p>The embedded instance stands in for the platform's own: this connects to it as the superuser
 * exactly as a deployment would, and every claim below is read back out of {@code pg_catalog}
 * rather than out of what the code said it did. That is the whole point of testing this half
 * against a server — the arms differ only in which statement runs, and a fake would be asserting
 * the test's own model of postgres.
 *
 * <p>Each test uses its own database and role name, since the instance is shared for the JVM.
 */
public class PgResourceProvisionerTest {

  private static final AtomicInteger COUNTER = new AtomicInteger();

  private static final String STORED = "0123456789abcdef0123456789abcdef";
  private static final String FRESH = "fedcba9876543210fedcba9876543210";

  private final PgResourceProvisioner provisioner = new PgResourceProvisioner();

  /** A name no other test in this JVM uses. */
  private static String name() {
    return "qits_prov_" + COUNTER.incrementAndGet() + "_" + Math.abs(System.nanoTime() % 100000);
  }

  private ResourceProvisioner.Request request(String database, String stored) {
    return new ResourceProvisioner.Request(
        "localhost",
        EmbeddedPg.port(),
        EmbeddedPg.USER,
        EmbeddedPg.PASSWORD,
        database,
        database,
        stored,
        FRESH);
  }

  private static boolean roleExists(String role) throws Exception {
    return single("select 1 from pg_roles where rolname = '" + role + "'") != null;
  }

  private static boolean databaseExists(String database) throws Exception {
    return single("select 1 from pg_database where datname = '" + database + "'") != null;
  }

  private static String ownerOf(String database) throws Exception {
    return single(
        "select pg_get_userbyid(datdba) from pg_database where datname = '" + database + "'");
  }

  private static String single(String query) throws Exception {
    try (Connection admin =
            DriverManager.getConnection(EmbeddedPg.adminUrl(), EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        Statement sql = admin.createStatement();
        ResultSet answered = sql.executeQuery(query)) {
      return answered.next() ? answered.getString(1) : null;
    }
  }

  private static void execute(String ddl) throws Exception {
    try (Connection admin =
            DriverManager.getConnection(EmbeddedPg.adminUrl(), EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        Statement sql = admin.createStatement()) {
      sql.execute(ddl);
    }
  }

  /** The proof that matters most: the credential the caller is handed actually logs in. */
  private static void assertCanLogIn(String database, String password) throws Exception {
    try (Connection owned =
        DriverManager.getConnection(
            "jdbc:postgresql://localhost:" + EmbeddedPg.port() + "/" + database,
            database,
            password)) {
      assertFalse(owned.isClosed());
    }
  }

  @Test
  public void nothingExistsSoBothAreCreatedAndTheFreshPasswordIsInEffect() throws Exception {
    String database = name();

    ResourceProvisioner.Result result = provisioner.ensure(request(database, null));

    assertTrue(result.ok(), String.valueOf(result.detail()));
    assertEquals(FRESH, result.passwordInEffect());
    assertTrue(roleExists(database));
    assertTrue(databaseExists(database));
    assertEquals(database, ownerOf(database), "the database is owned by its own role");
    assertCanLogIn(database, FRESH);
  }

  @Test
  public void aSecondRunChangesNothingAndKeepsTheRecordedPassword() throws Exception {
    // The ordinary redeployment, and the arm that must never rotate: the application is running on
    // that credential right now. The password is proven unchanged by logging in with it.
    String database = name();
    provisioner.ensure(request(database, null));
    execute("alter role " + database + " with password " + PgResourceProvisioner.literal(STORED));

    ResourceProvisioner.Result result = provisioner.ensure(request(database, STORED));

    assertTrue(result.ok(), String.valueOf(result.detail()));
    assertEquals(STORED, result.passwordInEffect());
    assertCanLogIn(database, STORED);
  }

  @Test
  public void aRoleThatVanishedIsRecreatedWithTheRecordedPassword() throws Exception {
    // Self-heal: the postgres volume was reset while running containers still hold the credential
    // the registry recorded, so the role comes back with THAT password and nothing has to redeploy.
    String database = name();
    provisioner.ensure(request(database, STORED));
    assertCanLogIn(database, STORED);
    execute("drop database " + database);
    execute("drop role " + database);
    assertFalse(roleExists(database));

    ResourceProvisioner.Result result = provisioner.ensure(request(database, STORED));

    assertTrue(result.ok(), String.valueOf(result.detail()));
    assertEquals(STORED, result.passwordInEffect(), "the recorded password, not a new one");
    assertCanLogIn(database, STORED);
  }

  @Test
  public void aRoleNothingRecordedIsRotatedToTheFreshPassword() throws Exception {
    // Reconcile: the registry database was reset, so nothing knows this role's password any more.
    // There is nothing to preserve, and the deployment about to start is the one that will be told
    // the new one.
    String database = name();
    provisioner.ensure(request(database, null));
    execute("alter role " + database + " with password " + PgResourceProvisioner.literal(STORED));

    ResourceProvisioner.Result result = provisioner.ensure(request(database, null));

    assertTrue(result.ok(), String.valueOf(result.detail()));
    assertEquals(FRESH, result.passwordInEffect());
    assertCanLogIn(database, FRESH);
  }

  @Test
  public void aDatabaseThatVanishedComesBackOwnedByItsRole() throws Exception {
    // The last matrix row: the row and the role survived, the database did not.
    String database = name();
    provisioner.ensure(request(database, STORED));
    execute("drop database " + database);
    assertFalse(databaseExists(database));

    ResourceProvisioner.Result result = provisioner.ensure(request(database, STORED));

    assertTrue(result.ok(), String.valueOf(result.detail()));
    assertEquals(STORED, result.passwordInEffect());
    assertEquals(database, ownerOf(database));
  }

  @Test
  public void aDatabaseSomebodyElseOwnsIsHandedBackToItsRole() throws Exception {
    // The half-provisioned state a failed run leaves behind: the database exists but its owner is
    // whoever created it. Re-asserting ownership on every run is what heals it.
    String database = name();
    execute("create database " + database);
    assertEquals(EmbeddedPg.USER, ownerOf(database));

    ResourceProvisioner.Result result = provisioner.ensure(request(database, null));

    assertTrue(result.ok(), String.valueOf(result.detail()));
    assertEquals(database, ownerOf(database));
  }

  @Test
  public void theDatabaseIsClosedToEveryRoleButItsOwn() throws Exception {
    // A SHARED instance: without the revoke, every role on it may connect to every database this
    // component creates — one application's store readable by the next application to be
    // provisioned. PG 18 already owns the public SCHEMA by pg_database_owner, so the
    // database-level revoke is the whole of what is left to say. The contrast is the assertion: a
    // database created without it answers the other way.
    String database = name();
    String outsider = name();
    String open = name();
    execute("create role " + outsider + " login");
    execute("create database " + open);
    provisioner.ensure(request(database, null));

    assertEquals(
        "false",
        single("select has_database_privilege('" + outsider + "', '" + database + "', 'connect')::text"),
        "a role with no business here cannot connect");
    assertEquals(
        "true",
        single("select has_database_privilege('" + outsider + "', '" + open + "', 'connect')::text"),
        "...which is not postgres' default, and is therefore the revoke's doing");
  }

  /** {@code has_schema_privilege} on {@code pg_toast}, asked IN {@code database}: it is per catalog. */
  private static String toastUsage(String database, String role) throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + EmbeddedPg.port() + "/" + database,
                EmbeddedPg.USER,
                EmbeddedPg.PASSWORD);
        Statement sql = admin.createStatement();
        ResultSet answered =
            sql.executeQuery(
                "select has_schema_privilege('" + role + "', 'pg_toast', 'USAGE')::text")) {
      answered.next();
      return answered.getString(1);
    }
  }

  @Test
  public void theRoleMayNameItsOwnToastAndOnlyInItsOwnDatabase() throws Exception {
    // qits-2248: the blob store's partition shrink scans pg_toast.pg_toast_NNN by name, and without
    // the grant the owning role is refused the schema. Granted in the role's database alone, and a
    // second ensure — every redeployment — re-asserts it without complaint.
    String database = name();
    String other = name();
    execute("create database " + other);

    ResourceProvisioner.Result first = provisioner.ensure(request(database, null));
    assertTrue(first.ok(), String.valueOf(first.detail()));
    assertEquals("true", toastUsage(database, database));

    ResourceProvisioner.Result again = provisioner.ensure(request(database, FRESH));
    assertTrue(again.ok(), String.valueOf(again.detail()));
    assertEquals("true", toastUsage(database, database), "idempotent on the next deployment");
    assertEquals(
        "false", toastUsage(other, database), "...and not postgres' default anywhere else");
  }

  @Test
  public void aDatabaseProvisionedBeforeTheGrantConvergesOnItsNextEnsure() throws Exception {
    // Existing databases have no grant; ensure runs on every deployment, so they heal there.
    String database = name();
    provisioner.ensure(request(database, null));
    try (Connection admin =
            DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + EmbeddedPg.port() + "/" + database,
                EmbeddedPg.USER,
                EmbeddedPg.PASSWORD);
        Statement sql = admin.createStatement()) {
      sql.execute("revoke usage on schema pg_toast from " + database);
    }
    assertEquals("false", toastUsage(database, database));

    ResourceProvisioner.Result result = provisioner.ensure(request(database, FRESH));

    assertTrue(result.ok(), String.valueOf(result.detail()));
    assertEquals("true", toastUsage(database, database));
  }

  @Test
  public void aDuplicateIsTheOutcomeThatWasAskedForAndNotAFailure() {
    // Both convergence steps check then create, and the window between the two is real: the deploy
    // worker is serial, but the bootstrap's own JDBC provisioning is not. 42P04 and 42710 say the
    // thing exists, which is what was wanted; anything else fails the deployment.
    assertTrue(PgResourceProvisioner.benign("42P04"), "duplicate_database");
    assertTrue(PgResourceProvisioner.benign("42710"), "duplicate_object");
    assertFalse(PgResourceProvisioner.benign("42501"), "insufficient_privilege is a real failure");
    assertFalse(PgResourceProvisioner.benign("28P01"), "a wrong admin password is a real failure");
    assertFalse(PgResourceProvisioner.benign(null));
  }

  @Test
  public void anIdentifierOutsideTheAllowlistNeverReachesTheServer() {
    // DDL cannot be parametrized, so this is where the allowlist earns its keep — and the check is
    // here rather than only at the parser because the value travelled through a record, a registry
    // row and a worker thread to get here.
    ResourceProvisioner.Result refused =
        provisioner.ensure(
            new ResourceProvisioner.Request(
                "localhost",
                EmbeddedPg.port(),
                EmbeddedPg.USER,
                EmbeddedPg.PASSWORD,
                "postgres; drop database x",
                "postgres; drop database x",
                null,
                FRESH));

    assertFalse(refused.ok());
    assertNotNull(refused.detail());
  }

  @Test
  public void aPasswordThatCouldChangeTheStatementIsRefusedRatherThanEscapedCleverly() {
    // Every password this component generates is 32 hex characters. This one is the belt for the
    // other source — a credential a deployment was handed by the bootstrap and recorded at boot.
    assertEquals("'plain'", PgResourceProvisioner.literal("plain"));
    assertEquals("'it''s'", PgResourceProvisioner.literal("it's"), "a quote is doubled, as SQL says");
    assertThrows(IllegalArgumentException.class, () -> PgResourceProvisioner.literal("back\\slash"));
    assertThrows(IllegalArgumentException.class, () -> PgResourceProvisioner.literal("new\nline"));
    assertThrows(IllegalArgumentException.class, () -> PgResourceProvisioner.literal(""));
    assertThrows(IllegalArgumentException.class, () -> PgResourceProvisioner.literal(null));
    assertThrows(
        IllegalArgumentException.class, () -> PgResourceProvisioner.literal("x".repeat(129)));
  }
}
