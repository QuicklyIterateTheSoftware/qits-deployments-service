package eu.wohlben.qits.deployments.deployments.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The database move's space arithmetic (qits-2243), with plain numbers. Plain JUnit. */
class MoveSpaceTest {

  private static final long GIB = 1L << 30;
  private static final long RESERVE = 5 * GIB;

  private static DatabaseScripts.Filesystem disk(String device, long free) {
    return new DatabaseScripts.Filesystem(device, 150 * GIB, free);
  }

  private static MoveSpace.Move move(long size, DatabaseScripts.Filesystem data, DatabaseScripts.Filesystem volume) {
    return new MoveSpace.Move(
        "public/qits_a", "public/qits_b", new MoveSpace.Measured(size, data, volume));
  }

  @Test
  void theIncidentsNumbersDoNotFitAndTheSentenceSaysWhy() {
    String refusal =
        MoveSpace.refusal(
            List.of(move(19 * GIB, disk("/dev/sda1", 42 * GIB), disk("/dev/sda1", 42 * GIB))),
            1.1,
            RESERVE);
    assertEquals(
        "refusing to move public/qits_a to public/qits_b: it is 19.0 GiB, and the target's filesystem"
            + " has 42.0 GiB free where the dump and the copy need 46.8 GiB"
            + " (qits.deployments.database-move.space-factor 1.1, space-reserve 5 GiB); free space"
            + " first",
        refusal);
  }

  @Test
  void exactlyTheRequirementFits() {
    long need = 2 * MoveSpace.copyBytes(19 * GIB, 1.1) + RESERVE;
    assertNull(
        MoveSpace.refusal(
            List.of(move(19 * GIB, disk("/dev/sda1", need), disk("/dev/sda1", need))), 1.1, RESERVE));
    assertTrue(
        MoveSpace.refusal(
                List.of(move(19 * GIB, disk("/dev/sda1", need - 1), disk("/dev/sda1", need - 1))),
                1.1,
                RESERVE)
            != null);
  }

  @Test
  void separateFilesystemsEachNeedOneCopyAndTheReserve() {
    // 19 GiB → 20.9 + 5 = 25.9 GiB on each.
    assertNull(
        MoveSpace.refusal(
            List.of(move(19 * GIB, disk("/dev/vdb", 26 * GIB), disk("/dev/sda1", 26 * GIB))),
            1.1,
            RESERVE));
    String volumeShort =
        MoveSpace.refusal(
            List.of(move(19 * GIB, disk("/dev/vdb", 100 * GIB), disk("/dev/sda1", 25 * GIB))),
            1.1,
            RESERVE);
    assertTrue(
        volumeShort.contains("the dump volume's filesystem has 25.0 GiB free where the dump needs 25.9 GiB"),
        volumeShort);
  }

  @Test
  void aBatchIsSummedPerFilesystem() {
    DatabaseScripts.Filesystem shared = disk("/dev/sda1", 40 * GIB);
    MoveSpace.Move one = move(10 * GIB, shared, shared);
    assertNull(MoveSpace.refusal(List.of(one), 1.1, RESERVE));
    String both =
        MoveSpace.refusal(
            List.of(one, new MoveSpace.Move("public/qits_c", "public/qits_d", one.measured())),
            1.1,
            RESERVE);
    assertTrue(both.contains("this batch's 2 dumps and 2 copies (2 moves) need 49.0 GiB"), both);
  }

  @Test
  void theReserveReadsAsASize() {
    assertEquals(5 * GIB, MoveSpace.parseSize("5G"));
    assertEquals(5 * GIB, MoveSpace.parseSize("5GiB"));
    assertEquals(512L << 20, MoveSpace.parseSize("512m"));
    assertEquals(1234, MoveSpace.parseSize("1234"));
    assertThrows(IllegalArgumentException.class, () -> MoveSpace.parseSize("five"));
    assertThrows(IllegalArgumentException.class, () -> MoveSpace.parseSize("-1G"));
    assertEquals("9.0 MiB", MoveSpace.human(9L << 20));
    assertEquals("5 GiB", MoveSpace.humanShort(5 * GIB));
  }
}
