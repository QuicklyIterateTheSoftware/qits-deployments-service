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
 *
 * <p>The space check's measures (qits-2243) run here the same way: {@link DatabaseScripts#sizeSql}
 * as written, and {@link DatabaseScripts#spaceSql} with the {@code df} command spelled out as the
 * literal psql's {@code \set} assembles — the server's own superuser running {@code df} through
 * {@code COPY FROM PROGRAM}, read back through the parser the move uses.
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
  void theSourceSizeIsTheServersOwnAnswer() throws SQLException {
    String database = provisioned();
    StringBuilder printed = new StringBuilder();
    try (Connection admin = admin("postgres");
        Statement asked = admin.createStatement();
        ResultSet rows = asked.executeQuery(DatabaseScripts.sizeSql(database))) {
      while (rows.next()) {
        printed.append(rows.getString(1)).append('\n');
      }
    }
    Long size = DatabaseScripts.sizeAnswer(printed.toString());
    assertTrue(size != null && size > 0, printed.toString());
  }

  @Test
  void theDataDirectorysFreeSpaceIsReadByTheServersOwnDf() throws SQLException {
    StringBuilder printed = new StringBuilder();
    try (Connection admin = admin("postgres")) {
      String dataDirectory;
      try (Statement asked = admin.createStatement();
          ResultSet rows = asked.executeQuery("SHOW data_directory")) {
        rows.next();
        dataDirectory = rows.getString(1);
      }
      // What `\set qits_df_cmd 'df -Pk "' :data_directory '"'` assembles, quoted by :'qits_df_cmd'.
      String literal = "'df -Pk \"" + dataDirectory.replace("'", "''") + "\"'";
      String[] statements = DatabaseScripts.spaceSql(literal).split(";\n");
      try (Statement run = admin.createStatement()) {
        run.execute(statements[0]);
        run.execute(statements[1]);
        try (ResultSet rows = run.executeQuery(statements[2])) {
          while (rows.next()) {
            printed.append(rows.getString(1)).append('\n');
          }
        }
      }
    }
    DatabaseScripts.Filesystem data =
        DatabaseScripts.filesystem(printed.toString(), DatabaseScripts.DATA_DF_MARKER);
    assertTrue(data != null, printed.toString());
    assertTrue(data.totalBytes() > 0 && data.freeBytes() >= 0, data.toString());
    assertTrue(data.freeBytes() <= data.totalBytes(), data.toString());
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

  private static boolean exists(Connection admin, String database) throws SQLException {
    try (Statement asked = admin.createStatement();
        ResultSet rows = asked.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + database + "'")) {
      return rows.next();
    }
  }

  /** {@link DatabaseScripts#renameInPlace}'s transaction, the password a literal for the test. */
  private static void renameInPlace(Connection admin, String from, String to) throws SQLException {
    try (Statement statement = admin.createStatement()) {
      statement.execute("BEGIN");
      try {
        statement.execute(DatabaseScripts.renameInPlaceSql(from, to));
        statement.execute("ALTER ROLE " + to + " WITH LOGIN PASSWORD 'role-password'");
        statement.execute(DatabaseScripts.reopenSql(to, to));
        statement.execute("COMMIT");
      } catch (SQLException failed) {
        statement.execute("ROLLBACK");
        throw failed;
      }
    }
  }

  @Test
  void aRenameInPlaceMovesTheDatabaseAndItsRoleAndIsTheWayBackToo() throws SQLException {
    // qits-2193: two names on one server are a rename, in a transaction, with the source fenced.
    String database = provisioned();
    String target = database + "_new";
    try (Connection admin = admin("postgres")) {
      execute(admin, DatabaseScripts.closeSql(database, database));
      renameInPlace(admin, database, target);
      assertFalse(exists(admin, database));
      assertTrue(exists(admin, target));
      // Idempotent: the answer lost, the same script again renames nothing and re-opens it.
      renameInPlace(admin, database, target);
    }
    // The renamed role owns the renamed database, connects with its password and reads its data.
    try (Connection owner = asRole(target, target);
        Statement read = owner.createStatement();
        ResultSet counted = read.executeQuery("SELECT count(*) FROM items")) {
      counted.next();
      assertEquals(3, counted.getLong(1), "the same data under another name");
    }
    assertThrows(SQLException.class, () -> asRole(target, database).close(), "the old role is gone");

    // The way back is the same script, names swapped.
    try (Connection admin = admin("postgres")) {
      execute(admin, DatabaseScripts.closeSql(target, target));
      renameInPlace(admin, target, database);
      assertTrue(exists(admin, database));
      assertFalse(exists(admin, target));
    }
    asRole(database, database).close();

    // Both names present is refused, and the transaction leaves both as they were.
    try (Connection admin = admin("postgres")) {
      execute(admin, "CREATE DATABASE " + target + ";\n");
      assertThrows(SQLException.class, () -> renameInPlace(admin, database, target));
      assertTrue(exists(admin, database));
      execute(admin, "DROP DATABASE " + target + ";\n");
    }
  }

  /** {@link DatabaseScripts#describeSql}'s answer, read the way the self-move reads it. */
  private static java.util.Map<String, DatabaseScripts.Described> describe(Connection admin, String... names)
      throws SQLException {
    StringBuilder output = new StringBuilder();
    try (Statement statement = admin.createStatement()) {
      for (String part : DatabaseScripts.describeSql(names).split(";\n")) {
        String sql = part.strip();
        if (sql.isEmpty()) {
          continue;
        }
        try (ResultSet rows = statement.executeQuery(sql.endsWith(";") ? sql : sql + ";")) {
          while (rows.next()) {
            output.append(rows.getString(1)).append('\n');
          }
        }
      }
    }
    return DatabaseScripts.describeAnswer(output.toString(), names);
  }

  @Test
  void theSelfMovesMarkIsTheDatabasesCommentAndTravelsWithARename() throws SQLException {
    // qits-2209: the deployer's own move keeps its state in the database's comment, because the
    // old and the new process each read a different database. The rename must carry it along.
    String database = provisioned();
    String target = database.replace("qits_fence_", "qits_marked_");
    try (Connection admin = admin("postgres")) {
      java.util.Map<String, DatabaseScripts.Described> before = describe(admin, database, target);
      assertTrue(before.get(database).exists());
      assertEquals("", before.get(database).mark());
      assertFalse(before.get(target).exists());

      execute(admin, DatabaseScripts.commentSql(database, "qits-self-move:m-1:renaming"));
      execute(admin, DatabaseScripts.closeSql(database, database));
      renameInPlace(admin, database, target);

      java.util.Map<String, DatabaseScripts.Described> after = describe(admin, database, target);
      assertFalse(after.get(database).exists());
      assertEquals("qits-self-move:m-1:renaming", after.get(target).mark());

      execute(admin, DatabaseScripts.commentSql(target, null));
      assertEquals("", describe(admin, target).get(target).mark());
      execute(admin, "DROP DATABASE " + target + " WITH (FORCE);\nDROP ROLE " + target + ";\n");
    }
    assertThrows(IllegalArgumentException.class, () -> DatabaseScripts.commentSql(target, "x' OR '1"));
  }
}
