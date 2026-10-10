package eu.wohlben.qits.deployments.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The recorder's machinery, without an application: freezing, rendering, comparing, filtering. */
class GoldenMasterMachineryTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static JsonNode json(String text) throws Exception {
    return JSON.readTree(text);
  }

  // --- freezing --------------------------------------------------------------------------------

  @Test
  void idsAreNumberedByFirstAppearanceAfterTheSeededParams() throws Exception {
    String project = UUID.randomUUID().toString();
    String a = UUID.randomUUID().toString();
    String b = UUID.randomUUID().toString();
    JsonNode answer =
        json(
            "{\"entries\":[{\"id\":\""
                + a
                + "\",\"projectId\":\""
                + project
                + "\"},{\"id\":\""
                + b
                + "\",\"projectId\":\""
                + project
                + "\",\"url\":\"/git/"
                + a
                + ".git\"}]}");

    Freezer freezer = new Freezer().seed(List.of(project));
    JsonNode frozen = freezer.freeze(answer);

    assertEquals(
        "{\"entries\":[{\"id\":\"00000000-0000-4000-8000-000000000002\","
            + "\"projectId\":\"00000000-0000-4000-8000-000000000001\"},"
            + "{\"id\":\"00000000-0000-4000-8000-000000000003\","
            + "\"projectId\":\"00000000-0000-4000-8000-000000000001\","
            + "\"url\":\"/git/00000000-0000-4000-8000-000000000002.git\"}]}",
        frozen.toString());
    assertEquals("00000000-0000-4000-8000-000000000001", freezer.freezeParam(project));
    assertEquals(List.of("$.entries[*].id", "$.entries[*].projectId"), freezer.idPaths());
    assertEquals(List.of("$.entries[*].url"), freezer.stringPaths());
  }

  @Test
  void onlyAWholeUuidIsAnIdPathAndAStringContainingOneIsAStringPath() throws Exception {
    String missing = UUID.randomUUID().toString();
    Freezer freezer = new Freezer().seed(List.of(missing));
    JsonNode frozen =
        freezer.freeze(
            json(
                "{\"id\":\""
                    + missing
                    + "\",\"message\":\"Project not found: "
                    + missing
                    + "\"}"));
    assertEquals(
        "{\"id\":\"00000000-0000-4000-8000-000000000001\","
            + "\"message\":\"Project not found: 00000000-0000-4000-8000-000000000001\"}",
        frozen.toString());
    assertEquals(List.of("$.id"), freezer.idPaths());
    assertEquals(List.of("$.message"), freezer.stringPaths());
    assertEquals(List.of(), freezer.instantPaths());
  }

  @Test
  void numbersAreHexInATwelveDigitLastGroup() {
    assertEquals("00000000-0000-4000-8000-00000000000a", Freezer.frozenId(10));
    assertEquals("00000000-0000-4000-8000-000000000010", Freezer.frozenId(16));
  }

  @Test
  void freezingIsDeterministicAcrossFreshIds() throws Exception {
    String first = recordWithFreshIds();
    String second = recordWithFreshIds();
    assertEquals(first, second);
  }

  private static String recordWithFreshIds() throws Exception {
    String project = UUID.randomUUID().toString();
    String repo = UUID.randomUUID().toString();
    String token = UUID.randomUUID().toString().substring(0, 8);
    JsonNode answer =
        json(
            "{\"repository\":{\"id\":\""
                + repo
                + "\",\"name\":\"contract-"
                + token
                + "-contract-"
                + token
                + "\",\"projectId\":\""
                + project
                + "\",\"lastBackup\":{\"at\":\"2026-09-30T12:34:56.789123Z\"}}}");
    Freezer freezer = new Freezer().seed(List.of(project, repo)).uniqueTokens(List.of(token));
    return GoldenJson.render(freezer.freeze(answer))
        + freezer.idPaths()
        + freezer.instantPaths()
        + freezer.stringPaths();
  }

  @Test
  void instantsAndUniqueTokensAreFrozenAndRecorded() throws Exception {
    Freezer freezer = new Freezer().uniqueTokens(List.of("3fa9c2e1"));
    JsonNode frozen =
        freezer.freeze(
            json(
                "{\"slug\":\"contract-3fa9c2e1\",\"at\":\"2026-09-30T12:34:56+02:00\","
                    + "\"day\":\"2026-09-30\",\"name\":\"fixed\"}"));
    assertEquals(
        "{\"slug\":\"contract-00000001\",\"at\":\"2026-01-01T00:00:00Z\","
            + "\"day\":\"2026-09-30\",\"name\":\"fixed\"}",
        frozen.toString());
    assertEquals(List.of("$.at"), freezer.instantPaths());
    assertEquals(List.of("$.slug"), freezer.stringPaths());
    assertEquals(List.of(), freezer.idPaths());
  }

  @Test
  void aKeyThatIsNotAPlainIdentifierIsBracketed() throws Exception {
    Freezer freezer = new Freezer();
    freezer.freeze(json("{\"odd-key\":\"" + UUID.randomUUID() + "\"}"));
    assertEquals(List.of("$['odd-key']"), freezer.idPaths());
  }

  // --- rendering -------------------------------------------------------------------------------

  @Test
  void filesAreTwoSpacePrettyPrintedWithATrailingNewline() throws Exception {
    assertEquals(
        "{\n  \"a\": 1,\n  \"b\": [\n    \"x\",\n    null\n  ],\n  \"c\": {},\n  \"d\": []\n}\n",
        GoldenJson.render(json("{\"a\":1,\"b\":[\"x\",null],\"c\":{},\"d\":[]}")));
  }

  // --- comparing -------------------------------------------------------------------------------

  @Test
  void aRecordedFileThatDiffersFromTheCommittedOneFails(@TempDir Path dir) throws Exception {
    Path golden = dir.resolve("getProject.json");
    Files.writeString(golden, "{\n  \"name\": \"before\"\n}\n");

    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () ->
                GoldenFiles.compareOrWrite(
                    golden, "{\n  \"name\": \"after\"\n}\n", false, UnaryOperator.identity()));

    assertTrue(failure.getMessage().contains("-  \"name\": \"before\""), failure.getMessage());
    assertTrue(failure.getMessage().contains("+  \"name\": \"after\""), failure.getMessage());
    assertTrue(failure.getMessage().contains("@@ -1,3 +1,3 @@"), failure.getMessage());
    assertTrue(
        failure.getMessage().indexOf("-  \"name\"") < failure.getMessage().indexOf("+  \"name\""),
        "the removed line reads before the added one");
    assertEquals("{\n  \"name\": \"before\"\n}\n", Files.readString(golden), "compare never writes");
  }

  @Test
  void anIdenticalFilePassesAndUpdateRewrites(@TempDir Path dir) throws Exception {
    Path golden = dir.resolve("nested").resolve("index.json");
    assertTrue(
        GoldenFiles.check(golden, "x\n", false, UnaryOperator.identity()).contains("No golden at"));
    assertNull(GoldenFiles.check(golden, "x\n", true, UnaryOperator.identity()));
    assertNull(GoldenFiles.check(golden, "x\n", false, UnaryOperator.identity()));
  }

  // --- the list filter -------------------------------------------------------------------------

  @Test
  void theListKeepsOnlyTheEntriesTheStateCreated() throws Exception {
    String mine = UUID.randomUUID().toString();
    String theirs = UUID.randomUUID().toString();
    JsonNode answer =
        json(
            "{\"entries\":[{\"project\":{\"id\":\""
                + theirs
                + "\"}},{\"project\":{\"id\":\""
                + mine
                + "\"}}]}");
    GoldenMasterRecordingTest.Interaction listing =
        new GoldenMasterRecordingTest.Interaction(
            "a project exists", "listProjects", "GET", "/x", 200, "$.entries", null);

    JsonNode kept = GoldenMasterRecordingTest.recordable(answer, listing, List.of(mine), List.of());

    assertEquals("{\"entries\":[{\"project\":{\"id\":\"" + mine + "\"}}]}", kept.toString());
  }

  @Test
  void aSortedListIgnoresTheRandomTokenInItsKey() throws Exception {
    GoldenMasterRecordingTest.Interaction listing =
        new GoldenMasterRecordingTest.Interaction(
            "s", "op", "GET", "/x", 200, null, "$.entries:repository.name");
    for (String token : List.of("00aa11bb", "ffee9988")) {
      JsonNode answer =
          json(
              "{\"entries\":[{\"repository\":{\"name\":\"contract-service\"}},"
                  + "{\"repository\":{\"name\":\"contract-"
                  + token
                  + "-contract-"
                  + token
                  + "\"}},{\"repository\":{\"name\":\"contract-daemon\"}}]}");
      JsonNode sorted =
          GoldenMasterRecordingTest.recordable(answer, listing, List.of(), List.of(token));
      assertEquals(
          "contract-" + token + "-contract-" + token,
          sorted.at("/entries/0/repository/name").asText());
      assertEquals("contract-daemon", sorted.at("/entries/1/repository/name").asText());
      assertEquals("contract-service", sorted.at("/entries/2/repository/name").asText());
    }
  }

  @Test
  void slugsFollowTheFormat() {
    assertEquals(
        "a-project-with-3-repositories", ProviderStates.slug("a project with 3 repositories"));
    assertEquals("no-project-with-the-given-id", ProviderStates.slug("No project, with the given id"));
  }
}
