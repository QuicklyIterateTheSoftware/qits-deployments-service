package eu.wohlben.qits.deployments.pacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link GoldenMasters#cut}: a pact binds only the paths the consumer reads. */
class GoldenMastersCutTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode json(String text) throws Exception {
    return MAPPER.readTree(text);
  }

  @Test
  void keepsOnlyTheConsumedPaths() throws Exception {
    JsonNode recorded =
        json("{\"events\":[{\"id\":\"a\",\"name\":\"X\",\"createdAt\":\"t\"},{\"id\":\"b\",\"name\":\"Y\",\"createdAt\":\"t\"}],\"nextCursor\":\"c\",\"extra\":1}");
    JsonNode cut = GoldenMasters.cut(recorded, List.of("events[].id", "nextCursor"));
    assertEquals(json("{\"events\":[{\"id\":\"a\"},{\"id\":\"b\"}],\"nextCursor\":\"c\"}"), cut);
  }

  @Test
  void aPathTheRecordingDoesNotHoldIsLeftOut() throws Exception {
    JsonNode cut = GoldenMasters.cut(json("{\"events\":[]}"), List.of("events[].id", "nextCursor"));
    assertEquals(json("{\"events\":[]}"), cut);
  }

  @Test
  void aNullThatIsHeldStays() throws Exception {
    JsonNode cut = GoldenMasters.cut(json("{\"parentId\":null,\"x\":1}"), List.of("parentId"));
    assertTrue(cut.has("parentId"));
    assertTrue(cut.get("parentId").isNull());
  }

  @Test
  void nothingReadIsStatusOnly() throws Exception {
    assertNull(GoldenMasters.cut(json("{\"x\":1}"), List.of()));
  }
}
