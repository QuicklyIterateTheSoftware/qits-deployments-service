package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The move's and the private provisioning's scripts, as text (qits-2175). Plain JUnit. */
class DatabaseScriptsTest {

  @Test
  void aKeptNameStaysAPostgresIdentifierHoweverLongTheDatabase() {
    Instant at = Instant.parse("2026-11-13T14:05:00Z");
    assertEquals("qits_orchestrator_moved_202611131405", DatabaseScripts.keptName("qits_orchestrator", at));

    String longest = "qits_" + "a".repeat(58);
    String kept = DatabaseScripts.keptName(longest, at);
    assertEquals(63, kept.length(), kept);
    assertTrue(kept.endsWith("_moved_202611131405"), kept);
  }

  @Test
  void theManifestIsReadBetweenItsMarkersAndOnlyThen() {
    String output =
        "pg_restore: noise\nqits-manifest-begin\npublic.a=3\nflyway.history=12\nqits-manifest-end\n";
    assertEquals(Map.of("public.a", 3L, "flyway.history", 12L), DatabaseScripts.manifest(output));
    assertEquals(Map.of(), DatabaseScripts.manifest("qits-manifest-begin\nqits-manifest-end\n"));
    assertNull(DatabaseScripts.manifest("qits-manifest-begin\npublic.a=3\n"), "never closed");
    assertNull(DatabaseScripts.manifest("qits-manifest-begin\npublic.a=many\nqits-manifest-end"));
  }

  @Test
  void theFirstDifferenceNamesTheTable() {
    assertNull(DatabaseScripts.firstDifference(Map.of("a.t", 1L), Map.of("a.t", 1L)));
    assertEquals(
        "a.t has 2 rows on the source and 1 on the target",
        DatabaseScripts.firstDifference(Map.of("a.t", 2L, "b.t", 1L), Map.of("a.t", 1L, "b.t", 9L)));
    assertEquals(
        "a.s is missing on the target (4 rows on the source)",
        DatabaseScripts.firstDifference(Map.of("a.s", 4L, "a.t", 1L), Map.of("a.t", 1L)));
  }

  @Test
  void identifiersAreRevalidatedAndNoScriptCarriesAPasswordOrAShellExpansion() {
    assertThrows(RuntimeException.class, () -> DatabaseScripts.provision("postgres", "postgres", false));
    assertThrows(RuntimeException.class, () -> DatabaseScripts.extract("qits_x; drop database y"));

    String provision = DatabaseScripts.provision("qits_x", "qits_x", true);
    assertTrue(provision.contains("\\getenv role_password QITS_ROLE_PASSWORD"), provision);
    assertTrue(provision.contains("ALTER ROLE qits_x WITH LOGIN PASSWORD :'role_password'"), provision);
    assertFalse(DatabaseScripts.provision("qits_x", "qits_x", false).contains("ALTER ROLE"));
    // The heredoc delimiter is quoted, so the shell expands nothing inside the SQL.
    assertTrue(provision.contains("<<'QITS_SQL'"), provision);
    assertTrue(DatabaseScripts.ingest("qits_y", "qits_y", false).contains("--role=qits_y"));
    assertFalse(DatabaseScripts.ingest("qits_y", "qits_y", false).contains("DROP DATABASE"));
    assertTrue(DatabaseScripts.ingest("qits_y", "qits_y", true).contains("DROP DATABASE IF EXISTS qits_y"));
  }

  @Test
  void theSizeIsReadOffItsMarkerAndNothingElse() {
    assertEquals(20401094656L, DatabaseScripts.sizeAnswer("noise\nqits-size=20401094656\n"));
    assertNull(DatabaseScripts.sizeAnswer("qits-size=\n"));
    assertNull(DatabaseScripts.sizeAnswer("qits-size=-1\n"));
    assertNull(DatabaseScripts.sizeAnswer("ERROR: database does not exist\n"));
    assertTrue(DatabaseScripts.size("qits_x").contains("pg_database_size('qits_x')"));
    assertThrows(RuntimeException.class, () -> DatabaseScripts.size("qits_x'); drop"));
  }

  @Test
  void aDfReadingIsItsLastMarkedLineWithTheFilesystemTotalAndFree() {
    String output =
        "qits-data-df=Filesystem     1024-blocks      Used Available Capacity Mounted on\n"
            + "qits-data-df=/dev/sda1       157286400 113246208  44040192      73% /var/lib/postgresql/data\n"
            + "qits-volume-df=/dev/sda1 157286400 113246208 44040190 73% /move\n";
    DatabaseScripts.Filesystem data = DatabaseScripts.filesystem(output, DatabaseScripts.DATA_DF_MARKER);
    DatabaseScripts.Filesystem volume = DatabaseScripts.filesystem(output, DatabaseScripts.VOLUME_DF_MARKER);
    assertEquals(new DatabaseScripts.Filesystem("/dev/sda1", 157286400L * 1024, 44040192L * 1024), data);
    assertEquals(44040190L * 1024, volume.freeBytes());
    assertTrue(data.sameAs(volume), "one device, one total: one filesystem, whatever is free");
    assertFalse(
        data.sameAs(new DatabaseScripts.Filesystem("/dev/sda1", 1024, data.freeBytes())),
        "another total is another filesystem");
    assertFalse(data.sameAs(new DatabaseScripts.Filesystem("overlay", data.totalBytes(), 0)));
    // The header alone, a cut line, a word where a number goes, nothing at all: no reading.
    assertNull(
        DatabaseScripts.filesystem(
            "qits-data-df=Filesystem 1024-blocks Used Available Capacity Mounted on\n",
            DatabaseScripts.DATA_DF_MARKER));
    assertNull(DatabaseScripts.filesystem("qits-volume-df=/dev/sda1 1 2\n", DatabaseScripts.VOLUME_DF_MARKER));
    assertNull(
        DatabaseScripts.filesystem(
            "qits-volume-df=/dev/sda1 many 2 3 4% /move\n", DatabaseScripts.VOLUME_DF_MARKER));
    assertNull(DatabaseScripts.filesystem("", DatabaseScripts.VOLUME_DF_MARKER));
  }

  @Test
  void theSpaceScriptBuildsItsDfCommandInPsqlAndMeasuresTheVolumeItself() {
    String space = DatabaseScripts.space();
    assertTrue(space.contains("SHOW data_directory \\gset"), space);
    assertTrue(space.contains("\\set qits_df_cmd 'df -Pk \"' :data_directory '\"'"), space);
    assertTrue(space.contains("COPY qits_df (line) FROM PROGRAM :'qits_df_cmd';"), space);
    assertTrue(space.contains("ORDER BY n"), space);
    assertTrue(space.contains("echo \"qits-volume-df=$(df -Pk /move | tail -n 1)\""), space);
    assertTrue(space.contains("<<'QITS_SQL'"), "the shell expands nothing inside the SQL");
  }

  @Test
  void aRenameInPlaceRenamesTheDatabaseAndItsRoleInOneTransactionAndOpensItAgain() {
    String script = DatabaseScripts.renameInPlace("qits_platform_x", "qits_x", false, true);
    assertTrue(script.contains("<<'QITS_SQL'"), script);
    assertTrue(script.contains("\\getenv role_password QITS_ROLE_PASSWORD"), script);
    int begin = script.indexOf("BEGIN;");
    int database = script.indexOf("ALTER DATABASE qits_platform_x RENAME TO qits_x;");
    int role = script.indexOf("ALTER ROLE qits_platform_x RENAME TO qits_x;");
    int password = script.indexOf("ALTER ROLE qits_x WITH LOGIN PASSWORD :'role_password';");
    int reopen = script.indexOf("GRANT CONNECT ON DATABASE qits_x TO qits_x;");
    int commit = script.indexOf("COMMIT;");
    assertTrue(
        begin > 0 && begin < database && database < role && role < password && password < reopen && reopen < commit,
        script);
    // Only while the source is there: a repeat after a lost answer renames nothing.
    assertTrue(script.contains("IF EXISTS (SELECT 1 FROM pg_database WHERE datname = 'qits_platform_x') THEN"), script);
    assertTrue(script.indexOf("pg_terminate_backend") < begin, "the source's backends go first");
    assertFalse(script.contains("DROP"), script);
    assertFalse(script.contains("pg_dump") || script.contains("pg_restore"), script);

    // An earlier aborted attempt's leftover is dropped only while the source is still there.
    String leftover = DatabaseScripts.renameInPlace("qits_platform_x", "qits_x", true, true);
    assertTrue(leftover.contains("SELECT 'DROP DATABASE qits_x WITH (FORCE)' WHERE EXISTS (SELECT 1 FROM pg_database"
        + " WHERE datname = 'qits_platform_x')"), leftover);
    assertTrue(leftover.indexOf("DROP ROLE qits_x") < leftover.indexOf("BEGIN;"), "DROP DATABASE cannot run in a transaction");

    // No password known: the role is renamed, and nothing sets one.
    String bare = DatabaseScripts.renameInPlace("qits_x", "qits_platform_x", false, false);
    assertFalse(bare.contains("getenv"), bare);
    assertFalse(bare.contains("PASSWORD"), bare);
    assertTrue(bare.contains("ALTER DATABASE qits_x RENAME TO qits_platform_x;"), bare);

    assertThrows(RuntimeException.class, () -> DatabaseScripts.renameInPlace("qits_x", "qits_y; drop", false, true));
    assertThrows(RuntimeException.class, () -> DatabaseScripts.renameInPlace("postgres", "qits_y", false, true));
  }

  @Test
  void theServerRuleIsOneMethod() {
    assertEquals(DatabaseServer.PRIVATE, DatabaseServer.forExposure(DeploymentDriver.Exposure.PRIVATE));
    assertEquals(DatabaseServer.PUBLIC, DatabaseServer.forExposure(DeploymentDriver.Exposure.PUBLIC));
    assertEquals(DatabaseServer.PUBLIC, DatabaseServer.forExposure(null));
    assertEquals("dev-qits-oci-postgresql-private", DatabaseServer.PRIVATE.host("dev"));
    assertEquals(DatabaseServer.PUBLIC, DatabaseServer.parse(null));
    assertThrows(IllegalArgumentException.class, () -> DatabaseServer.parse("elsewhere"));
  }

  @Test
  void theSelfMovesStateIsTheDatabasesCommentReadAndWrittenAsText() {
    // qits-2209: describe names each database once, and its answer reads back by name.
    String described = DatabaseScripts.describe("qits_deployments", "qits_platform_deployments");
    assertTrue(described.contains("'qits-db=qits_deployments='"), described);
    assertTrue(described.contains("shobj_description(oid, 'pg_database')"), described);
    Map<String, DatabaseScripts.Described> answer =
        DatabaseScripts.describeAnswer(
            "qits-db=qits_deployments=true=qits-self-move:m:verified\nqits-db=qits_platform_deployments=false=\n",
            "qits_deployments",
            "qits_platform_deployments");
    assertEquals(new DatabaseScripts.Described(true, "qits-self-move:m:verified"), answer.get("qits_deployments"));
    assertEquals(new DatabaseScripts.Described(false, ""), answer.get("qits_platform_deployments"));
    assertNull(
        DatabaseScripts.describeAnswer("qits-db=qits_deployments=true=\n", "qits_deployments", "qits_other"),
        "a name the output does not answer is no answer");

    assertTrue(DatabaseScripts.comment("qits_deployments", "qits-self-move:m:done").contains(
        "COMMENT ON DATABASE qits_deployments IS 'qits-self-move:m:done';"));
    assertTrue(DatabaseScripts.comment("qits_deployments", null).contains("COMMENT ON DATABASE qits_deployments IS NULL;"));
    assertThrows(IllegalArgumentException.class, () -> DatabaseScripts.comment("qits_deployments", "x'; DROP"));

    // The ingest marks the database it creates in the same session, before the restore.
    String ingest = DatabaseScripts.ingest("qits_deployments", "qits_deployments", true, "qits-self-move:m:copying");
    assertTrue(
        ingest.indexOf("COMMENT ON DATABASE qits_deployments IS 'qits-self-move:m:copying';")
            < ingest.indexOf("pg_restore"),
        ingest);
    assertFalse(DatabaseScripts.ingest("qits_deployments", "qits_deployments", false).contains("COMMENT"));
  }
}
