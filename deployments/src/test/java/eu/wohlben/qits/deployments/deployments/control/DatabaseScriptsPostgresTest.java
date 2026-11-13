package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.persistence.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The move's FENCE against a real postgres (qits-2175) — zonky's, the suite's own.
 *
 * <p><b>Why only the fence, and over JDBC.</b> The binaries zonky ships carry {@code initdb}, {@code
 * pg_ctl} and {@code postgres} and nothing else — no {@code psql}, no {@code pg_dump}, no {@code
 * pg_restore} — so the scripts themselves cannot run here. What can, and what a fake hid, is the
 * semantics the extract depends on: after {@link DatabaseScripts#closeSql} the application's role is
 * shut out while the admin still connects (the dump connects as the admin), {@link
 * DatabaseScripts#reopenSql} lets the role back in, and the {@code ALLOW_CONNECTIONS false} the
 * first cut used shut the admin out too — which is the bug it had.
 */
class DatabaseScriptsPostgresTest {

  private static Connection admin(String database) throws SQLException {
    return DriverManager.getConnection(
        "jdbc:postgresql://localhost:" + EmbeddedPg.port() + "/" + database,
        EmbeddedPg.USER,
        EmbeddedPg.PASSWORD);
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      for (String part : sql.split(";\n")) {
        String statementText = part.strip();
        if (!statementText.isEmpty()) {
          statement.execute(statementText.endsWith(";") ? statementText : statementText + ";");
        }
      }
    }
  }

  private static Connection asRole(String database, String role) throws SQLException {
    return DriverManager.getConnection(
        "jdbc:postgresql://localhost:" + EmbeddedPg.port() + "/" + database, role, "role-password");
  }

  /** A provisioned database as PgResourceProvisioner leaves it: owned by its role, PUBLIC revoked. */
  private static String provisioned() throws SQLException {
    String name = "qits_fence_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    try (Connection admin = admin("postgres")) {
      execute(admin, "CREATE ROLE " + name + " LOGIN PASSWORD 'role-password';\n");
      execute(admin, "CREATE DATABASE " + name + " OWNER " + name + ";\n");
      execute(admin, "REVOKE ALL ON DATABASE " + name + " FROM public;\n");
    }
    try (Connection owner = asRole(name, name)) {
      execute(owner, "CREATE TABLE items (id int);\nINSERT INTO items VALUES (1), (2), (3);\n");
    }
    return name;
  }

  @Test
  void theFenceShutsTheRoleOutAndLetsTheAdminDumpIt() throws SQLException {
    String database = provisioned();
    try (Connection admin = admin("postgres")) {
      execute(admin, DatabaseScripts.closeSql(database, database));
    }

    SQLException refused = assertThrows(SQLException.class, () -> asRole(database, database).close());
    assertTrue(refused.getMessage().contains("permission denied"), refused.getMessage());
    try (Connection dump = admin(database);
        Statement read = dump.createStatement();
        ResultSet counted = read.executeQuery("SELECT count(*) FROM items")) {
      counted.next();
      assertEquals(3, counted.getLong(1), "the admin reads what pg_dump would");
    }

    // An abort re-opens it to its role.
    try (Connection admin = admin("postgres")) {
      execute(admin, DatabaseScripts.reopenSql(database, database));
    }
    asRole(database, database).close();
  }

  @Test
  void theKeptCopyIsRenamedFencedAndClosedAndStillDropsWithForce() throws SQLException {
    String database = provisioned();
    String kept = database + "_moved_202601010000";
    try (Connection admin = admin("postgres")) {
      execute(admin, DatabaseScripts.closeSql(database, database));
      execute(admin, "ALTER DATABASE " + database + " RENAME TO " + kept + ";\n");
      execute(admin, "ALTER DATABASE " + kept + " WITH ALLOW_CONNECTIONS false;\n");
    }
    assertThrows(SQLException.class, () -> asRole(kept, database).close());
    // ALLOW_CONNECTIONS false shuts out the admin too — why the fence before the dump is a
    // privilege and not this flag.
    SQLException closed = assertThrows(SQLException.class, () -> admin(kept).close());
    assertTrue(closed.getMessage().contains("not currently accepting connections"), closed.getMessage());

    try (Connection admin = admin("postgres")) {
      execute(admin, "DROP DATABASE IF EXISTS " + kept + " WITH (FORCE);\nDROP ROLE IF EXISTS " + database + ";\n");
      try (Statement asked = admin.createStatement();
          ResultSet rows = asked.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + kept + "'")) {
        assertFalse(rows.next(), "the sweep's drop needs no connection to the kept copy");
      }
    }
  }
}
