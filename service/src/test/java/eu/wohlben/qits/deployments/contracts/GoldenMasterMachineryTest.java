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
import java.util.Map;
import java.util.TreeMap;
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

  // --- sticky id numbers (qits-1917) ------------------------------------------------------------

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private static String f(int n) {
    return Freezer.frozenId(n);
  }

  @Test
  void withoutPinsParamsNumberInKeyOrderAsBefore() throws Exception {
    Map<String, String> params = new TreeMap<>(Map.of("b", id(), "a", id(), "c", "4711"));
    String other = id();
    Freezer pinned = new Freezer().seed(params, Map.of());
    Freezer legacy = new Freezer().seed(params.values());
    JsonNode answer = json("{\"x\":\"" + other + "\",\"a\":\"" + params.get("a") + "\"}");

    assertEquals(f(1), pinned.freezeParam(params.get("a")));
    assertEquals(f(2), pinned.freezeParam(params.get("b")));
    assertEquals(legacy.freeze(answer), pinned.freeze(answer));
    assertEquals("{\"x\":\"" + f(3) + "\",\"a\":\"" + f(1) + "\"}", pinned.freeze(answer).toString());
  }

  @Test
  void aRemovedParamLeavesAGapAndTheOthersKeepTheirNumbers() throws Exception {
    // committed: a=1, gone=2, c=3; "gone" is gone now
    Map<String, String> committed = Map.of("a", f(1), "gone", f(2), "c", f(3));
    Map<String, String> params = Map.of("a", id(), "c", id());
    String answerOnly = id();
    Freezer freezer = new Freezer().seed(params, committed);

    assertEquals(f(1), freezer.freezeParam(params.get("a")));
    assertEquals(f(3), freezer.freezeParam(params.get("c")));
    assertEquals("\"" + f(4) + "\"", freezer.freeze(json("\"" + answerOnly + "\"")).toString());
  }

  @Test
  void anAddedParamTakesTheHighestNumberPlusOne() {
    // committed: b=1, d=2; "a" sorts first but is new
    Map<String, String> committed = Map.of("b", f(1), "d", f(2));
    Map<String, String> params = Map.of("a", id(), "b", id(), "d", id());
    Freezer freezer = new Freezer().seed(params, committed);

    assertEquals(f(3), freezer.freezeParam(params.get("a")));
    assertEquals(f(1), freezer.freezeParam(params.get("b")));
    assertEquals(f(2), freezer.freezeParam(params.get("d")));
  }

  @Test
  void answerOnlyIdsNumberAboveEveryPinnedOne() throws Exception {
    // a pin above a gap: the answer's ids go above it, never into the gap
    Map<String, String> committed = Map.of("a", f(1), "z", f(7));
    Map<String, String> params = Map.of("a", id(), "z", id());
    String x = id();
    String y = id();
    Freezer freezer = new Freezer().seed(params, committed);

    assertEquals(
        "[\"" + f(8) + "\",\"" + f(9) + "\",\"" + f(7) + "\"]",
        freezer.freeze(json("[\"" + x + "\",\"" + y + "\",\"" + params.get("z") + "\"]")).toString());
  }

  @Test
  void aCommittedValueNotOfTheFrozenFormPinsNothing() {
    Map<String, String> params = Map.of("a", id(), "b", id());
    Map<String, String> committed = Map.of("a", id(), "b", "00000000-0000-4000-8000-00000000000g");
    Freezer freezer = new Freezer().seed(params, committed);

    assertEquals(f(1), freezer.freezeParam(params.get("a")));
    assertEquals(f(2), freezer.freezeParam(params.get("b")));
  }

  @Test
  void twoParamsNamingOneEntityShareTheFirstNumber() {
    String shared = id();
    // committed (inconsistently) gave them 2 and 5: the first in key order wins
    Map<String, String> committed = Map.of("a", f(2), "b", f(5), "c", f(1));
    Map<String, String> params = Map.of("a", shared, "b", shared.toUpperCase(), "c", id());
    Freezer freezer = new Freezer().seed(params, committed);

    assertEquals(f(2), freezer.freezeParam(params.get("a")));
    assertEquals(f(2), freezer.freezeParam(params.get("b")));
    assertEquals(f(1), freezer.freezeParam(params.get("c")));
  }

  @Test
  void aNumberPinnedTwiceToDifferentEntitiesPinsOnlyTheFirst() {
    Map<String, String> committed = Map.of("a", f(1), "b", f(1));
    Map<String, String> params = Map.of("a", id(), "b", id());
    Freezer freezer = new Freezer().seed(params, committed);

    assertEquals(f(1), freezer.freezeParam(params.get("a")));
    assertEquals(f(2), freezer.freezeParam(params.get("b")));
  }

  @Test
  void aUuidEmbeddedInACursorKeepsItsCommittedNumber() throws Exception {
    // "events to catch up on, fifty at a time": the cursor sorts first and was …0001, the exact
    // event ids …0002-4; the cursor must not be renumbered above them
    Map<String, String> committed =
        Map.of(
            "cursor", "2100-01-01T00:00:00Z," + f(1),
            "firstEventId", f(2),
            "secondEventId", f(3),
            "thirdEventId", f(4));
    Map<String, String> params =
        Map.of(
            "cursor", "2100-01-01T00:00:00Z,00000000-0000-0000-0000-000000000000",
            "firstEventId", id(),
            "secondEventId", id(),
            "thirdEventId", id());
    Freezer freezer = new Freezer().seed(params, committed);

    assertEquals("2100-01-01T00:00:00Z," + f(1), freezer.freezeParam(params.get("cursor")));
    assertEquals(f(2), freezer.freezeParam(params.get("firstEventId")));
    assertEquals(f(3), freezer.freezeParam(params.get("secondEventId")));
    assertEquals(f(4), freezer.freezeParam(params.get("thirdEventId")));
    assertEquals("\"" + f(5) + "\"", freezer.freeze(json("\"" + id() + "\"")).toString());
  }

  @Test
  void anEmbeddedUuidCountThatDiffersFromTheCommittedOnePinsNothingInThatParam() {
    Map<String, String> committed = Map.of("a", f(1), "cursor", "x," + f(5));
    String first = id();
    String second = id();
    Map<String, String> params = Map.of("a", id(), "cursor", first + "," + second);
    Freezer freezer = new Freezer().seed(params, committed);

    assertEquals(f(1), freezer.freezeParam(params.get("a")));
    assertEquals(f(2) + "," + f(3), freezer.freezeParam(params.get("cursor")));
  }

  @Test
  void committedParamsAreReadPerSlugAndAbsentWithoutAnIndex(@TempDir Path dir) throws Exception {
    assertEquals(Map.of(), GoldenMasterRecordingTest.committedParams(dir.resolve("index.json")));
    Files.writeString(
        dir.resolve("index.json"),
        "{\"states\":[{\"slug\":\"s\",\"params\":{\"eventId\":\"" + f(1) + "\"}}]}");
    assertEquals(
        Map.of("s", Map.of("eventId", f(1))),
        GoldenMasterRecordingTest.committedParams(dir.resolve("index.json")));
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
