package eu.wohlben.qits.deployments.deployments.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Whether a batch of database moves fits on the disks it will be written to (qits-2243), as pure
 * arithmetic over what {@link DatabaseScripts#size} and {@link DatabaseScripts#space} measured. No
 * I/O, so the suite asserts it with plain numbers — the incident's among them.
 *
 * <p><b>Why it exists: a move filled the node's disk.</b> A same-server move of a 19 GB database on a
 * filesystem with 42 GB free wrote a dump into its volume and a restored copy into the data
 * directory, both on the one filesystem postgres itself runs on, until there were 0 bytes left and
 * every service on that postgres went down with it.
 *
 * <p><b>The requirement.</b> Each copy — the dump in the move's volume, and the restored database in
 * the target's data directory — counts as {@code factor × size} ({@code
 * qits.deployments.database-move.space-factor}, {@code 1.1} shipped: a custom-format dump is about the
 * size of the database, and a restore writes WAL on top). Every filesystem also keeps {@code
 * qits.deployments.database-move.space-reserve} free ({@code 5G} shipped). Two readings whose {@code
 * df} device and total size are equal are ONE filesystem, and its copies are summed; the sum runs over
 * every move of the batch, so two same-server moves need room for both.
 */
final class MoveSpace {

  static final String FACTOR_KEY = "qits.deployments.database-move.space-factor";
  static final String RESERVE_KEY = "qits.deployments.database-move.space-reserve";

  private static final long KIB = 1024;
  private static final long MIB = KIB * 1024;
  private static final long GIB = MIB * 1024;
  private static final long TIB = GIB * 1024;

  private MoveSpace() {}

  /**
   * What was measured for one move: the source's size, the target data directory's filesystem and
   * the move volume's. Any of them null when that measure failed.
   */
  record Measured(Long sourceBytes, DatabaseScripts.Filesystem data, DatabaseScripts.Filesystem volume) {

    Long targetFreeBytes() {
      return data == null ? null : data.freeBytes();
    }

    Long volumeFreeBytes() {
      return volume == null ? null : volume.freeBytes();
    }
  }

  /** One move as the check sees it: a name for the sentence, and what was measured. */
  record Move(String source, String target, Measured measured) {}

  /** The verdict: null when the batch fits, else the sentence the first short filesystem refuses with. */
  static String refusal(List<Move> batch, double factor, long reserve) {
    Map<String, Usage> usage = new LinkedHashMap<>();
    for (Move move : batch) {
      Measured measured = move.measured();
      long copy = copyBytes(measured.sourceBytes(), factor);
      boolean shared = measured.data().sameAs(measured.volume());
      usage(usage, measured.volume(), shared ? Role.BOTH : Role.VOLUME).add(move, copy, true);
      usage(usage, measured.data(), shared ? Role.BOTH : Role.DATA).add(move, copy, false);
    }
    for (Usage fs : usage.values()) {
      long need = fs.bytes + reserve;
      if (fs.free < need) {
        Move first = fs.moves.get(0);
        return "refusing to move "
            + first.source()
            + " to "
            + first.target()
            + ": it is "
            + human(first.measured().sourceBytes())
            + ", and "
            + fs.role.what
            + " has "
            + human(fs.free)
            + " free where "
            + fs.writes()
            + (fs.moves.size() == 1 && (fs.dumps == 0 || fs.copies == 0) ? " needs " : " need ")
            + human(need)
            + " ("
            + FACTOR_KEY
            + " "
            + factorText(factor)
            + ", space-reserve "
            + humanShort(reserve)
            + "); free space first";
      }
    }
    return null;
  }

  /** The one INFO sentence of a started move: its size, the free space, what it needs. */
  static String describe(Measured measured, double factor, long reserve) {
    long copy = copyBytes(measured.sourceBytes(), factor);
    String size = human(measured.sourceBytes());
    if (measured.data().sameAs(measured.volume())) {
      return size
          + "; the target's filesystem has "
          + human(Math.min(measured.data().freeBytes(), measured.volume().freeBytes()))
          + " free, the dump and the copy need "
          + human(2 * copy + reserve);
    }
    return size
        + "; the dump volume's filesystem has "
        + human(measured.volume().freeBytes())
        + " free and needs "
        + human(copy + reserve)
        + ", the target's data directory has "
        + human(measured.data().freeBytes())
        + " free and needs "
        + human(copy + reserve);
  }

  /** {@code ceil(factor × size)}: what one copy of the source is counted as. */
  static long copyBytes(long sourceBytes, double factor) {
    return (long) Math.ceil(sourceBytes * factor);
  }

  /**
   * A size as configured: a byte count, or a number with {@code K}, {@code M}, {@code G} or {@code
   * T} (binary, {@code 1024}-based; a trailing {@code B} or {@code iB} is allowed). {@code 5G} is five
   * GiB.
   *
   * @throws IllegalArgumentException naming the value when it reads as no size
   */
  static long parseSize(String text) {
    String value = text == null ? "" : text.strip().toUpperCase(Locale.ROOT);
    if (value.endsWith("IB")) {
      value = value.substring(0, value.length() - 2);
    } else if (value.endsWith("B")) {
      value = value.substring(0, value.length() - 1);
    }
    long unit = 1;
    if (!value.isEmpty()) {
      switch (value.charAt(value.length() - 1)) {
        case 'K' -> unit = KIB;
        case 'M' -> unit = MIB;
        case 'G' -> unit = GIB;
        case 'T' -> unit = TIB;
        default -> unit = 1;
      }
      if (unit != 1) {
        value = value.substring(0, value.length() - 1).strip();
      }
    }
    try {
      long number = Long.parseLong(value);
      if (number < 0) {
        throw new NumberFormatException();
      }
      return Math.multiplyExact(number, unit);
    } catch (NumberFormatException | ArithmeticException unreadable) {
      throw new IllegalArgumentException(
          RESERVE_KEY + " is not a size: `" + text + "` (a byte count, or a number with K, M, G or T)");
    }
  }

  /** {@code 19.0 GiB}, {@code 9.0 MiB}: one decimal, the largest binary unit under the value. */
  static String human(long bytes) {
    if (bytes >= TIB) {
      return String.format(Locale.ROOT, "%.1f TiB", bytes / (double) TIB);
    }
    if (bytes >= GIB) {
      return String.format(Locale.ROOT, "%.1f GiB", bytes / (double) GIB);
    }
    if (bytes >= MIB) {
      return String.format(Locale.ROOT, "%.1f MiB", bytes / (double) MIB);
    }
    if (bytes >= KIB) {
      return String.format(Locale.ROOT, "%.1f KiB", bytes / (double) KIB);
    }
    return bytes + " B";
  }

  /** {@link #human} without a {@code .0}: {@code 5 GiB}, the way a configured value reads. */
  static String humanShort(long bytes) {
    return human(bytes).replace(".0 ", " ");
  }

  private static String factorText(double factor) {
    return java.math.BigDecimal.valueOf(factor).stripTrailingZeros().toPlainString();
  }

  /** Which of a move's two writes a filesystem holds. */
  private enum Role {
    BOTH("the target's filesystem"),
    VOLUME("the dump volume's filesystem"),
    DATA("the target's data directory filesystem");

    final String what;

    Role(String what) {
      this.what = what;
    }
  }

  private static Usage usage(
      Map<String, Usage> usage, DatabaseScripts.Filesystem filesystem, Role role) {
    Usage fs =
        usage.computeIfAbsent(
            filesystem.device() + "|" + filesystem.totalBytes(), key -> new Usage(role, filesystem.freeBytes()));
    // Readings taken a moment apart of one filesystem: the smaller is the one to trust.
    fs.free = Math.min(fs.free, filesystem.freeBytes());
    if (fs.role != role) {
      fs.role = Role.BOTH;
    }
    return fs;
  }

  /** What one filesystem is asked to hold, summed over the batch. */
  private static final class Usage {
    Role role;
    long free;
    long bytes;
    int dumps;
    int copies;
    final List<Move> moves = new ArrayList<>();

    Usage(Role role, long free) {
      this.role = role;
      this.free = free;
    }

    void add(Move move, long copy, boolean dump) {
      bytes += copy;
      if (dump) {
        dumps++;
      } else {
        copies++;
      }
      if (!moves.contains(move)) {
        moves.add(move);
      }
    }

    String writes() {
      if (moves.size() == 1) {
        if (dumps > 0 && copies > 0) {
          return "the dump and the copy";
        }
        return dumps > 0 ? "the dump" : "the copy";
      }
      List<String> parts = new ArrayList<>();
      if (dumps > 0) {
        parts.add(dumps + (dumps == 1 ? " dump" : " dumps"));
      }
      if (copies > 0) {
        parts.add(copies + (copies == 1 ? " copy" : " copies"));
      }
      return "this batch's " + String.join(" and ", parts) + " (" + moves.size() + " moves)";
    }
  }
}
