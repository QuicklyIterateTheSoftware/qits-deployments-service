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
  void theServerRuleIsOneMethod() {
    assertEquals(DatabaseServer.PRIVATE, DatabaseServer.forExposure(DeploymentDriver.Exposure.PRIVATE));
    assertEquals(DatabaseServer.PUBLIC, DatabaseServer.forExposure(DeploymentDriver.Exposure.PUBLIC));
    assertEquals(DatabaseServer.PUBLIC, DatabaseServer.forExposure(null));
    assertEquals("dev-qits-oci-postgresql-private", DatabaseServer.PRIVATE.host("dev"));
    assertEquals(DatabaseServer.PUBLIC, DatabaseServer.parse(null));
    assertThrows(IllegalArgumentException.class, () -> DatabaseServer.parse("elsewhere"));
  }
}
