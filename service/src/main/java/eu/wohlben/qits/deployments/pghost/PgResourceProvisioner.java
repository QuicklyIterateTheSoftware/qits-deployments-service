package eu.wohlben.qits.deployments.pghost;

import eu.wohlben.qits.deployments.deployments.control.ResourceProvisioner;
import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.jboss.logging.Logger;

/**
 * The sole production implementation of {@link ResourceProvisioner}: plain JDBC against the
 * platform's postgres, as the administrator, over the driver this component already carries for its
 * own store.
 *
 * <p><b>No second datasource, on purpose.</b> Agroal pools are for a store this process owns; this
 * is a handful of idempotent statements against somebody else's server, run once per deployment, at
 * an address derived per deployment. A {@link DriverManager} connection opened and closed around
 * them is the honest shape, and it keeps the admin credential out of every datasource-shaped
 * surface a config dump would print.
 *
 * <p><b>Autocommit stays on, and it has to.</b> {@code CREATE DATABASE} cannot run inside a
 * transaction block — postgres refuses it outright — so this converges statement by statement. Each
 * statement is idempotent on its own, which is what makes a half-finished run safe to repeat.
 *
 * <p><b>DDL cannot be parametrized, so the identifiers are re-validated immediately before the
 * string is assembled.</b> That is the health-path stance applied to the one place it matters most:
 * everything here is repository-authored input reaching a server the whole platform shares. The
 * existence checks below ARE parametrized — they read {@code pg_catalog}, where a bind variable is
 * available and therefore mandatory.
 *
 * <p><b>No statement containing a password is ever logged</b>, not at debug and not in a failure
 * detail. What is logged is the shape of what happened: which role, which database, which arm.
 */
@ApplicationScoped
public class PgResourceProvisioner implements ResourceProvisioner {

  private static final Logger LOG = Logger.getLogger(PgResourceProvisioner.class);

  /** {@code duplicate_database} — another connection created it between the check and the CREATE. */
  private static final String DUPLICATE_DATABASE = "42P04";

  /** {@code duplicate_object} — the same race, for the role. */
  private static final String DUPLICATE_OBJECT = "42710";

  /** The database every postgres has, and the only one an admin can be sure of connecting to. */
  private static final String ADMIN_DATABASE = "postgres";

  @Override
  public Result ensure(Request request) {
    String database;
    String role;
    try {
      // Immediately before any string is assembled, and deliberately not once at the boundary and
      // then trusted: the value travelled through a record, a registry row and a worker thread to
      // get here, and DDL has no bind variables to fall back on.
      database = PdIdentifiers.requireDatabaseName(request.databaseName());
      role = PdIdentifiers.requireDatabaseName(request.roleName());
    } catch (RuntimeException e) {
      return new Result(false, null, "refused an identifier: " + e.getMessage());
    }

    String url = "jdbc:postgresql://" + request.host() + ":" + request.port() + "/" + ADMIN_DATABASE;
    try (Connection admin =
        DriverManager.getConnection(url, request.adminUsername(), request.adminPassword())) {
      // CREATE DATABASE refuses to run in a transaction block; every statement here is idempotent
      // on its own, so committing each is also what makes a half-finished run safe to repeat.
      admin.setAutoCommit(true);

      String passwordInEffect = ensureRole(admin, role, request);
      ensureDatabase(admin, database, role);
      ensureToastUsage(request, database, role);
      return new Result(true, passwordInEffect, null);
    } catch (SQLException e) {
      // The message is postgres', and postgres does not put passwords in its errors — but the
      // statement text would, so nothing here echoes one.
      return new Result(false, null, "postgres refused: " + e.getMessage());
    } catch (RuntimeException e) {
      return new Result(false, null, "could not provision: " + e.getMessage());
    }
  }

  /**
   * Converge the login role, and answer which password it now has.
   *
   * <ul>
   *   <li><b>missing</b> → create it with the stored password when there is one (the postgres
   *       volume was reset and every running container still holds the old credential), otherwise
   *       with the fresh one;
   *   <li><b>present and the registry has a row</b> → touch nothing. This is the ordinary
   *       redeployment, and it is the arm that must never rotate anything: the application is
   *       running on that password right now;
   *   <li><b>present and the registry has no row</b> → rotate it to the fresh password. The
   *       registry is the single authority for the credential and it no longer knows this one, so
   *       there is nothing to preserve — this is the reconcile after a deployer database reset.
   * </ul>
   */
  private String ensureRole(Connection admin, String role, Request request) throws SQLException {
    boolean exists = exists(admin, "select 1 from pg_roles where rolname = ?", role);
    if (!exists) {
      String password = request.storedPassword() != null ? request.storedPassword() : request.freshPassword();
      execute(admin, "create role " + role + " login password " + literal(password));
      LOG.infof("Created role %s", role);
      return password;
    }
    if (request.storedPassword() != null) {
      LOG.debugf("Role %s exists and its password is on record — left alone", role);
      return request.storedPassword();
    }
    execute(admin, "alter role " + role + " with login password " + literal(request.freshPassword()));
    LOG.infof("Reconciled role %s: nothing recorded its password, so it was rotated", role);
    return request.freshPassword();
  }

  /**
   * Converge the database and its ownership.
   *
   * <p>A missing database is created owned by the role and then closed to {@code PUBLIC} — on
   * postgres 18 the {@code public} schema is already owned by {@code pg_database_owner}, so the
   * database-level revoke is the whole of what is left to say. A database that exists has its owner
   * re-asserted instead, which heals the half-provisioned state a failed run leaves behind.
   */
  private void ensureDatabase(Connection admin, String database, String role) throws SQLException {
    if (!exists(admin, "select 1 from pg_database where datname = ?", database)) {
      try {
        execute(admin, "create database " + database + " owner " + role);
        execute(admin, "revoke all on database " + database + " from public");
        LOG.infof("Created database %s owned by %s", database, role);
        return;
      } catch (SQLException e) {
        if (!benign(e.getSQLState())) {
          throw e;
        }
        // Somebody created it between the check and the create. That is the outcome asked for.
        LOG.debugf("Database %s appeared between the check and the create", database);
      }
    }
    execute(admin, "alter database " + database + " owner to " + role);
  }

  /**
   * Grant the role {@code USAGE} on schema {@code pg_toast} in its own database (qits-2248).
   *
   * <p><b>Why.</b> The shared blob store (qits-blobstore, in qits-artifacts and qits-mirror) gives
   * disk back from an oversized partition by finding the chunks in the last pages of the
   * partition's TOAST relation — a TID range scan on {@code pg_toast.pg_toast_NNN} — moving them,
   * and letting VACUUM truncate the emptied end. The role owns that table and its TOAST, yet is
   * refused with {@code permission denied for schema pg_toast}: nobody but a superuser has {@code
   * USAGE} there. A privilege-free heuristic was tried live instead and moved a GiB to give back 770
   * KB, so the role has to be able to name its own TOAST.
   *
   * <p><b>Why this is narrow.</b> Schema {@code USAGE} only lets a role resolve names in it; every
   * toast table keeps its owner's privileges, so the role reads the TOAST of tables it already owns
   * and nothing of anyone else's — and the grant is per database, made in this one alone.
   *
   * <p><b>A second connection, to the target.</b> A schema grant is a statement about one database's
   * catalog, so it cannot be made from {@code postgres}. {@code GRANT} is idempotent, and since
   * {@code ensure} runs on every deployment, a database provisioned before this converges on its
   * next one — a moved database included, whose deployment ensures it after the switch.
   */
  private static void ensureToastUsage(Request request, String database, String role)
      throws SQLException {
    String url = "jdbc:postgresql://" + request.host() + ":" + request.port() + "/" + database;
    try (Connection target =
        DriverManager.getConnection(url, request.adminUsername(), request.adminPassword())) {
      target.setAutoCommit(true);
      execute(target, toastUsageSql(role));
    }
    LOG.debugf("Granted %s usage on schema pg_toast in %s", role, database);
  }

  /** The grant itself, the identifier re-validated as every DDL string here is. Package-private. */
  static String toastUsageSql(String role) {
    return "grant usage on schema pg_toast to " + PdIdentifiers.requireDatabaseName(role);
  }

  /**
   * Whether postgres' refusal was "it is already there".
   *
   * <p>The {@code alreadyJoined} arrangement, and a sturdier one: docker's wording is prose and
   * this is an SQLSTATE, which is an interface. Both convergence steps check then create, and the
   * window between the two is real — the deploy worker is serial but the bootstrap's own JDBC
   * provisioning is not, and a duplicate is exactly the outcome that was asked for. Package-private
   * for the test that pins both codes; anything else is a real failure and fails the deployment.
   */
  static boolean benign(String sqlState) {
    return DUPLICATE_DATABASE.equals(sqlState) || DUPLICATE_OBJECT.equals(sqlState);
  }

  private static boolean exists(Connection admin, String query, String name) throws SQLException {
    try (PreparedStatement asked = admin.prepareStatement(query)) {
      asked.setString(1, name);
      try (ResultSet answered = asked.executeQuery()) {
        return answered.next();
      }
    }
  }

  private static void execute(Connection admin, String ddl) throws SQLException {
    try (Statement statement = admin.createStatement()) {
      statement.execute(ddl);
    }
  }

  /**
   * A password as a SQL string literal.
   *
   * <p><b>Two belts, and the second is the one that matters.</b> The passwords this component
   * generates are 32 hex characters, so nothing it wrote can carry a quote — but a password can
   * also arrive from a deployment's own environment (this component's own database credential,
   * created by the bootstrap and recorded at boot), and that one is only as tame as whoever
   * generated it. So the value is quoted properly, by doubling any single quote as SQL says to, and
   * anything that could still change the meaning of the statement — a backslash, a control
   * character, an unbounded length — is refused outright rather than escaped cleverly.
   */
  static String literal(String password) {
    if (password == null
        || password.isEmpty()
        || password.length() > 128
        || password.chars().anyMatch(c -> c < 0x20 || c == 0x7f || c == '\\')) {
      // Deliberately says nothing about the value itself.
      throw new IllegalArgumentException("the password for this resource cannot be used in SQL");
    }
    return "'" + password.replace("'", "''") + "'";
  }
}
