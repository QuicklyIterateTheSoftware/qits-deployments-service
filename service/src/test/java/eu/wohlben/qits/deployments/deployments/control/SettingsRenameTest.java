package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.deployments.deployments.dto.PdSettingDto;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@link Settings#copyForRename} and {@link Settings#retire} — the two settings-side operations
 * qits-1399's rename and retirement cutover will call in the same transaction as the resource
 * claim transfer (qits-1397's {@code ResourceProvisioning.ensureAll}). Nothing calls either method
 * yet, so this suite drives them directly rather than through a door.
 *
 * <p>Every case uses application and environment names of its own, because the suite shares one
 * database across classes.
 */
@QuarkusTest
class SettingsRenameTest {

  @Inject Settings settings;

  private PdSettingDto put(String app, String env, String key, String value, boolean secret) {
    return settings.put(app, env, key, value, secret, "test-writer");
  }

  private List<PdSettingDto> listOf(String app, String env) {
    return settings.list(app, env);
  }

  @Test
  void copyForRenameCopiesOnlyWhatTheSuccessorDoesNotAlreadyHave() {
    String env = "ren-dev";
    put("ren-pred", env, "A", "a-value", false);
    put("ren-pred", env, "B", "b-secret-value", true);
    // The successor already has its own B — the copy must not clobber it.
    put("ren-succ", env, "B", "own-b-value", false);

    List<String> copied = settings.copyForRename("ren-pred", "ren-succ", env);

    assertEquals(List.of("A"), copied);

    Map<String, PdSettingDto> successorRows =
        listOf("ren-succ", env).stream()
            .collect(Collectors.toMap(PdSettingDto::key, row -> row));
    assertEquals(2, successorRows.size());

    PdSettingDto copiedA = successorRows.get("A");
    assertEquals("a-value", copiedA.value());
    assertTrue(!copiedA.secret());
    assertEquals("rename from ren-pred", copiedA.updatedBy());

    // B was untouched — still the successor's own value, not the predecessor's secret one.
    PdSettingDto untouchedB = successorRows.get("B");
    assertEquals("own-b-value", untouchedB.value());
    assertTrue(!untouchedB.secret());
    assertEquals("test-writer", untouchedB.updatedBy());

    // The predecessor's own rows are untouched by a copy.
    assertEquals(2, listOf("ren-pred", env).size());
  }

  @Test
  void copyPreservesTheSecretFlagOnASecretCopy() {
    String env = "ren-secret-dev";
    put("ren-secret-pred", env, "TOKEN", "super-secret", true);

    List<String> copied = settings.copyForRename("ren-secret-pred", "ren-secret-succ", env);

    assertEquals(List.of("TOKEN"), copied);
    PdSettingDto row =
        listOf("ren-secret-succ", env).stream()
            .filter(r -> r.key().equals("TOKEN"))
            .findFirst()
            .orElseThrow();
    assertTrue(row.secret());
    // The door masks a secret value, which is the right answer here too: nothing about this
    // copy should make a secret's value newly readable.
    assertEquals(null, row.value());
    assertEquals("rename from ren-secret-pred", row.updatedBy());
  }

  @Test
  void aSecondRunCopiesNothing() {
    String env = "ren-idempotent-dev";
    put("ren-idem-pred", env, "A", "a", false);

    List<String> first = settings.copyForRename("ren-idem-pred", "ren-idem-succ", env);
    List<String> second = settings.copyForRename("ren-idem-pred", "ren-idem-succ", env);

    assertEquals(List.of("A"), first);
    assertEquals(List.of(), second);
    assertEquals(1, listOf("ren-idem-succ", env).size());
  }

  @Test
  void anotherEnvironmentsRowsAreNotCopied() {
    String devEnv = "ren-env-dev";
    String prodEnv = "ren-env-prod";
    put("ren-env-pred", devEnv, "A", "dev-value", false);
    put("ren-env-pred", prodEnv, "A", "prod-value", false);

    List<String> copied = settings.copyForRename("ren-env-pred", "ren-env-succ", devEnv);

    assertEquals(List.of("A"), copied);
    assertEquals(1, listOf("ren-env-succ", devEnv).size());
    // Nothing landed in prod for the successor — the predecessor's prod row was never asked about.
    assertEquals(0, listOf("ren-env-succ", prodEnv).size());
  }

  @Test
  void renamedFromEqualToTheApplicationIsANoOp() {
    String env = "ren-self-dev";
    put("ren-self", env, "A", "a", false);

    List<String> copied = settings.copyForRename("ren-self", "ren-self", env);

    assertEquals(List.of(), copied);
  }

  @Test
  void aNullRenamedFromIsANoOp() {
    String env = "ren-null-dev";

    List<String> copied = settings.copyForRename(null, "ren-null-succ", env);

    assertEquals(List.of(), copied);
  }

  @Test
  void retireDeletesOnlyThatApplicationsRowsInThatEnvironment() {
    String devEnv = "ret-dev";
    String prodEnv = "ret-prod";
    put("ret-app", devEnv, "A", "a", false);
    put("ret-app", devEnv, "B", "b", true);
    put("ret-app", prodEnv, "A", "prod-a", false);
    put("ret-other-app", devEnv, "A", "other", false);

    int deleted = settings.retire("ret-app", devEnv);

    assertEquals(2, deleted);
    assertEquals(0, listOf("ret-app", devEnv).size());
    // Other environment and other application are both untouched.
    assertEquals(1, listOf("ret-app", prodEnv).size());
    assertEquals(1, listOf("ret-other-app", devEnv).size());
  }

  @Test
  void retireIsIdempotent() {
    String env = "ret-idem-dev";
    put("ret-idem-app", env, "A", "a", false);

    int first = settings.retire("ret-idem-app", env);
    int second = settings.retire("ret-idem-app", env);

    assertEquals(1, first);
    assertEquals(0, second);
  }
}
