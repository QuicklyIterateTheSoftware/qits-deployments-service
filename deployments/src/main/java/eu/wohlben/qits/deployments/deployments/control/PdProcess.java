package eu.wohlben.qits.deployments.deployments.control;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * This component's own tiny process shell-out — the {@code CiProcess} shape, deliberately its own
 * copy rather than a shared lib so this context stays movable (the same reasoning that kept the
 * forward-auth pair duplicated per service until it earned a jar). Combined stdout+stderr, drained
 * on a virtual thread; on timeout the process is {@link Process#destroyForcibly() force-killed} and
 * whatever output was captured so far is returned with {@code timedOut=true}.
 *
 * <p><b>What it is used for is the docker CLI and nothing else.</b> This component never executes
 * application code: its docker vocabulary is container lifecycle ({@code pull}, {@code run},
 * {@code inspect}, {@code logs}, {@code rm}, {@code ps}, {@code network} create/inspect/rm) and
 * {@code exec} is not in it. What a deployed container runs is its image's own entrypoint,
 * untouched.
 *
 * <p>Output is <b>bounded while reading</b>: the buffer keeps only the trailing {@code maxChars}.
 * A pull or a log capture can be arbitrarily chatty, and buffering it whole would let one
 * deployment OOM the JVM.
 */
public final class PdProcess {

  /** Rolling buffer slack: trim back to {@code maxChars} once it grows past this multiple. */
  private static final int TRIM_FACTOR = 2;

  /**
   * Exit code, bounded combined output, whether the hard timeout expired ({@code exitCode} is -1
   * then), and whether output was dropped from the front.
   */
  public record Result(int exitCode, String output, boolean timedOut, boolean truncated) {}

  private PdProcess() {}

  /** The variable the docker CLI reads its config directory from. */
  static final String DOCKER_CONFIG = "DOCKER_CONFIG";

  public static Result run(Path cwd, List<String> command, Duration timeout, int maxChars) {
    return run(cwd, command, null, timeout, maxChars);
  }

  /**
   * {@link #run(Path, List, Duration, int)}, with {@code DOCKER_CONFIG} pointed at {@code
   * dockerConfigDir} when — and only when — {@link #dockerConfig} says so. Otherwise the child
   * inherits this process's environment untouched, which is the mounted {@code /work/config}.
   */
  public static Result run(
      Path cwd, List<String> command, Path dockerConfigDir, Duration timeout, int maxChars) {
    try {
      ProcessBuilder pb = new ProcessBuilder(command);
      if (cwd != null) {
        pb.directory(cwd.toFile());
      }
      dockerConfig(dockerConfigDir).ifPresent(dir -> pb.environment().put(DOCKER_CONFIG, dir));
      pb.redirectErrorStream(true);
      Process process = pb.start();
      Tail tail = new Tail(maxChars);
      Thread reader =
          Thread.startVirtualThread(
              () -> {
                try (InputStream stream = process.getInputStream()) {
                  byte[] buffer = new byte[8192];
                  int n;
                  while ((n = stream.read(buffer)) >= 0) {
                    tail.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                  }
                } catch (Exception ignored) {
                  // stream closes when the process dies — nothing to report beyond the exit code
                }
              });
      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor();
      }
      reader.join(TimeUnit.SECONDS.toMillis(5));
      return new Result(
          finished ? process.exitValue() : -1, tail.text(), !finished, tail.truncated());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(-1, "interrupted", false, false);
    } catch (Exception e) {
      return new Result(-1, String.valueOf(e.getMessage()), false, false);
    }
  }

  /**
   * The {@code DOCKER_CONFIG} a docker child is given: {@code dir} when {@link
   * DockerCredentialFile} has written a {@code config.json} there, nothing otherwise — and nothing
   * means the child inherits the container's own value. Decided per child rather than once at boot,
   * so a call that races the startup write keeps the mounted credential instead of naming an empty
   * directory, and the next one picks the file up.
   */
  public static Optional<String> dockerConfig(Path dir) {
    if (dir == null || !Files.isRegularFile(dir.resolve(DockerCredentialFile.FILE_NAME))) {
      return Optional.empty();
    }
    return Optional.of(dir.toString());
  }

  /** A synchronized rolling tail — appends are trimmed so memory stays O(maxChars). */
  private static final class Tail {

    private final StringBuilder buffer = new StringBuilder();
    private final int maxChars;
    private boolean truncated;

    Tail(int maxChars) {
      this.maxChars = Math.max(1, maxChars);
    }

    synchronized void append(String chunk) {
      buffer.append(chunk);
      if (buffer.length() > (long) maxChars * TRIM_FACTOR) {
        buffer.delete(0, buffer.length() - maxChars);
        truncated = true;
      }
    }

    synchronized String text() {
      if (buffer.length() > maxChars) {
        buffer.delete(0, buffer.length() - maxChars);
        truncated = true;
      }
      return buffer.toString();
    }

    synchronized boolean truncated() {
      return truncated;
    }
  }
}
