package eu.wohlben.qits.deployments.deployments.control;

import eu.wohlben.qits.deployments.environments.control.PdIdentifiers;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every script a {@link DatabaseContainers.OneShot} runs (qits-2175), as pure functions: identifiers
 * in, a shell script out. No I/O and no state, so the suite asserts them as text.
 *
 * <p><b>DDL has no bind variables, so every identifier is re-validated right here</b> — {@link
 * PdIdentifiers#requireDatabaseName}, the {@code PgResourceProvisioner} stance: {@code qits_} and
 * lowercase letters, digits and underscores, which is also a charset a shell and a SQL string literal
 * take without one escaping rule. Nothing here quotes cleverly because nothing here needs to.
 *
 * <p><b>No password is ever in a script.</b> The admin's arrives as {@code PGPASSWORD}, which psql,
 * pg_dump and pg_restore read themselves; a role's arrives as {@value #ROLE_PASSWORD} and psql reads it
 * with {@code \getenv} into a variable that {@code :'role_password'} quotes as a literal. Both are
 * {@link DatabaseContainers.OneShot#secrets}, which never reach an argv.
 *
 * <p>Each psql runs with {@code -X} (no {@code .psqlrc}), {@code -v ON_ERROR_STOP=1} and under {@code
 * set -eu}, so any failing statement fails the container and the container's exit code is the verdict.
 */
final class DatabaseScripts {

  /** The variable a role's password reaches a container in. */
  static final String ROLE_PASSWORD = "QITS_ROLE_PASSWORD";

  /** Where the dump lands in the move's volume. */
  static final String DUMP = DatabaseContainers.MOUNT + "/dump.pgc";

  static final String MANIFEST_BEGIN = "qits-manifest-begin";
  static final String MANIFEST_END = "qits-manifest-end";

  /** The marker {@link #exists} prints before its answer. */
  static final String EXISTS_MARKER = "qits-exists=";

  /** The marker {@link #size} prints before the source's size in bytes (qits-2243). */
  static final String SIZE_MARKER = "qits-size=";

  /** The marker {@link #space} prints before each line of the data directory's {@code df -Pk}. */
  static final String DATA_DF_MARKER = "qits-data-df=";

  /** The marker {@link #space} prints before the move volume's {@code df -Pk} line. */
  static final String VOLUME_DF_MARKER = "qits-volume-df=";

  /** The database every postgres has, and the one an admin can be sure of connecting to. */
  static final String ADMIN_DATABASE = "postgres";

  /** A kept copy's suffix: {@code _moved_} and the minute of the switch, UTC. */
  private static final DateTimeFormatter KEPT_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

  /** Postgres' identifier limit. */
  private static final int NAME_MAX = 63;

  private DatabaseScripts() {}

  /** One psql session over {@code sql}, against {@code database}, under {@code set -eu}. */
  static String psql(String database, String sql) {
    return "set -eu\n" + psqlStep(database, sql, null);
  }

  private static String psqlStep(String database, String sql, String redirect) {
    return "psql -X -q -t -A -v ON_ERROR_STOP=1 -d "
        + (ADMIN_DATABASE.equals(database) ? ADMIN_DATABASE : id(database))
        + " <<'QITS_SQL'"
        + (redirect == null ? "" : " > " + redirect)
        + "\n"
        + sql
        + "\nQITS_SQL\n";
  }

  /**
   * The private server's provisioning — {@code PgResourceProvisioner}'s statements, as one psql
   * session. The role is created when missing (with {@value #ROLE_PASSWORD}, which the caller set to
   * the stored password, or the fresh one when nothing was stored) and left alone when present — or,
   * with {@code rotate}, reset to it: the reconcile arm, for a role the registry knows no password
   * for. The database is created owned by the role and closed to {@code PUBLIC}, or re-owned.
   */
  static String provision(String database, String role, boolean rotate) {
    String db = id(database);
    String r = id(role);
    String sql =
        "\\getenv role_password "
            + ROLE_PASSWORD
            + "\n"
            + "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
            + r
            + "') AS role_existed \\gset\n"
            + "\\if :role_existed\n"
            + (rotate ? "ALTER ROLE " + r + " WITH LOGIN PASSWORD :'role_password';\n" : "")
            + "\\else\n"
            + "CREATE ROLE "
            + r
            + " LOGIN PASSWORD :'role_password';\n"
            + "\\endif\n"
            + "SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = '"
            + db
            + "') AS db_existed \\gset\n"
            + "\\if :db_existed\n"
            + "ALTER DATABASE "
            + db
            + " OWNER TO "
            + r
            + ";\n"
            + "\\else\n"
            + "CREATE DATABASE "
            + db
            + " OWNER "
            + r
            + ";\n"
            + "REVOKE ALL ON DATABASE "
            + db
            + " FROM public;\n"
            + "\\endif";
    return psql(ADMIN_DATABASE, sql);
  }

  /** Prints {@value #EXISTS_MARKER}{@code t} or {@code f}: whether the database exists there. */
  static String exists(String database) {
    return psql(
        ADMIN_DATABASE,
        "SELECT '"
            + EXISTS_MARKER
            + "' || EXISTS (SELECT 1 FROM pg_database WHERE datname = '"
            + id(database)
            + "');");
  }

  /** The answer {@link #exists} printed, or null when it printed none. */
  static Boolean existsAnswer(String output) {
    for (String line : lines(output)) {
      if (line.startsWith(EXISTS_MARKER)) {
        return "t".equals(line.substring(EXISTS_MARKER.length()).strip());
      }
    }
    return null;
  }

  /**
   * The source's size, for the move's space check (qits-2243): prints {@value #SIZE_MARKER}{@code
   * <bytes>}, {@code pg_database_size} — what the database occupies on disk, which is what a dump
   * and a restored copy of it are measured against.
   */
  static String size(String database) {
    return psql(ADMIN_DATABASE, sizeSql(database));
  }

  /** {@link #size}'s statement, plain SQL — what the real-postgres test runs over JDBC. */
  static String sizeSql(String database) {
    return "SELECT '" + SIZE_MARKER + "' || pg_database_size('" + id(database) + "');";
  }

  /**
   * The free space a move needs, read where it will be spent (qits-2243): the TARGET server's data
   * directory, by its own superuser's {@code COPY ... FROM PROGRAM 'df -Pk <data_directory>'} — so
   * the {@code df} runs inside the postgres server's own container and measures its real data
   * filesystem, wherever that is — and the move's volume, by a {@code df -Pk} of {@link
   * DatabaseContainers#MOUNT} in this one-shot. Prints every line of the first behind {@value
   * #DATA_DF_MARKER}, in {@code df}'s order, and the last line of the second behind {@value
   * #VOLUME_DF_MARKER}.
   *
   * <p>{@code COPY FROM PROGRAM} takes a literal, so the command is assembled by psql itself: {@code
   * \gset} reads {@code data_directory} into a variable and {@code \set} concatenates it into the
   * command, which {@code :'qits_df_cmd'} then quotes as that literal. The path is the server's own
   * setting and reaches no shell of this process.
   */
  static String space() {
    return "set -eu\n"
        + psqlStep(ADMIN_DATABASE, SPACE_SQL, null)
        + "echo \""
        + VOLUME_DF_MARKER
        + "$(df -Pk "
        + DatabaseContainers.MOUNT
        + " | tail -n 1)\"\n";
  }

  /** {@link #space}'s psql half. */
  private static final String SPACE_SQL =
      "SHOW data_directory \\gset\n"
          + "\\set qits_df_cmd 'df -Pk \"' :data_directory '\"'\n"
          + spaceSql(":'qits_df_cmd'");

  /**
   * The SQL {@link #space} runs once psql has assembled the command, with {@code command} the
   * literal that names it — what the real-postgres test runs over JDBC with the literal spelled out.
   */
  static String spaceSql(String command) {
    return "CREATE TEMP TABLE qits_df (n serial, line text);\n"
        + "COPY qits_df (line) FROM PROGRAM "
        + command
        + ";\n"
        + "SELECT '"
        + DATA_DF_MARKER
        + "' || line FROM qits_df ORDER BY n;";
  }

  /** The size {@link #size} printed, or null when it printed none that reads as a byte count. */
  static Long sizeAnswer(String output) {
    for (String line : lines(output)) {
      if (line.startsWith(SIZE_MARKER)) {
        try {
          long bytes = Long.parseLong(line.substring(SIZE_MARKER.length()).strip());
          return bytes < 0 ? null : bytes;
        } catch (NumberFormatException unreadable) {
          return null;
        }
      }
    }
    return null;
  }

  /**
   * One filesystem as {@code df -Pk} names it: the device column and the total size identify it
   * (two readings with both equal are one filesystem), {@code free} is the Available column.
   */
  record Filesystem(String device, long totalBytes, long freeBytes) {

    boolean sameAs(Filesystem other) {
      return other != null && device.equals(other.device) && totalBytes == other.totalBytes;
    }
  }

  /**
   * The LAST line behind {@code marker} in {@code output}, read as a {@code df -Pk} data line —
   * {@code <device> <1K-blocks> <used> <available> <capacity> <mounted on>} — or null when there is
   * none or it does not read: the header, a truncated line, a non-number.
   */
  static Filesystem filesystem(String output, String marker) {
    String last = null;
    for (String line : lines(output)) {
      if (line.startsWith(marker)) {
        last = line.substring(marker.length()).strip();
      }
    }
    if (last == null) {
      return null;
    }
    String[] fields = last.split("\\s+");
    if (fields.length < 6) {
      return null;
    }
    try {
      long total = Long.parseLong(fields[1]);
      long available = Long.parseLong(fields[3]);
      if (total < 0 || available < 0) {
        return null;
      }
      return new Filesystem(fields[0], total * 1024, available * 1024);
    } catch (NumberFormatException unreadable) {
      return null;
    }
  }

  /**
   * Fence the source: its role — and {@code PUBLIC}, already revoked at provisioning and revoked
   * again idempotently — lose {@code CONNECT}, and every backend still open is terminated. The
   * service is already scaled to zero; this is what makes the dump the last word.
   *
   * <p><b>By privilege, never by {@code ALLOW_CONNECTIONS false}.</b> That flag shuts out EVERYONE,
   * superusers included, so the {@code pg_dump} that follows — which connects as the admin — died
   * with {@code database "x" is not currently accepting connections}. A superuser bypasses the
   * {@code CONNECT} privilege, so the fence keeps the application out and lets the dump in.
   */
  static String close(String database, String role) {
    return psql(ADMIN_DATABASE, closeSql(database, role));
  }

  /** {@link #close}'s statements, plain SQL — what the real-postgres test runs over JDBC. */
  static String closeSql(String database, String role) {
    String db = id(database);
    String r = id(role);
    return "REVOKE CONNECT ON DATABASE "
        + db
        + " FROM PUBLIC;\n"
        + "REVOKE CONNECT ON DATABASE "
        + db
        + " FROM "
        + r
        + ";\n"
        + "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE datname = '"
        + db
        + "' AND pid <> pg_backend_pid();";
  }

  /**
   * Re-open the source to its role — an abort's last word to postgres. An abort only ever happens
   * before the switch, so the source is still there under its own name.
   */
  static String reopen(String database, String role) {
    return psql(ADMIN_DATABASE, reopenSql(database, role));
  }

  /** {@link #reopen}'s statement, plain SQL. */
  static String reopenSql(String database, String role) {
    return "GRANT CONNECT ON DATABASE " + id(database) + " TO " + id(role) + ";";
  }

  /**
   * The extract: {@code pg_dump -Fc} of the source into the volume, then the source's manifest —
   * written into the volume beside the dump, and printed between the markers for the caller.
   */
  static String extract(String database) {
    String db = id(database);
    String manifest = DatabaseContainers.MOUNT + "/source.counts";
    return "set -eu\n"
        + "pg_dump -d "
        + db
        + " -Fc -f "
        + DUMP
        + "\n"
        + psqlStep(db, COUNTS, manifest)
        + printManifest(manifest);
  }

  /**
   * The ingest: the target role (with the claim's own password, so the service's credential does not
   * change) and the target database owned by it — dropping first only what an earlier aborted attempt
   * of this same move left behind — then the restore and the target's manifest.
   *
   * <p>{@code --no-owner --role=<role>} makes every restored object the role's; {@code
   * --no-privileges} drops the dump's grants, which on a cross-name move would name a role the target
   * server does not have. One role per database is the platform's shape, so there is nothing else for
   * a grant to say.
   */
  static String ingest(String database, String role, boolean dropLeftover) {
    String db = id(database);
    String r = id(role);
    String manifest = DatabaseContainers.MOUNT + "/target.counts";
    String sql =
        "\\getenv role_password "
            + ROLE_PASSWORD
            + "\n"
            + "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
            + r
            + "') AS role_existed \\gset\n"
            + "\\if :role_existed\n"
            + "ALTER ROLE "
            + r
            + " WITH LOGIN PASSWORD :'role_password';\n"
            + "\\else\n"
            + "CREATE ROLE "
            + r
            + " LOGIN PASSWORD :'role_password';\n"
            + "\\endif\n"
            + (dropLeftover ? "DROP DATABASE IF EXISTS " + db + " WITH (FORCE);\n" : "")
            + "CREATE DATABASE "
            + db
            + " OWNER "
            + r
            + ";\n"
            + "REVOKE ALL ON DATABASE "
            + db
            + " FROM public;";
    return "set -eu\n"
        + psqlStep(ADMIN_DATABASE, sql, null)
        + "pg_restore -d "
        + db
        + " --no-owner --no-privileges --role="
        + r
        + " --exit-on-error "
        + DUMP
        + "\n"
        + psqlStep(db, COUNTS, manifest)
        + printManifest(manifest);
  }

  /**
   * The switch's last statement to the source: rename it aside, still fenced — and then, since
   * nothing ever connects to a kept copy again, {@code ALLOW_CONNECTIONS false} on it as well. The
   * retention sweep's {@code DROP ... WITH (FORCE)} needs no connection to it. Idempotent: a source
   * already renamed renames nothing, and the kept copy is closed whether or not this run renamed it.
   */
  static String rename(String from, String to) {
    String source = id(from);
    String kept = id(to);
    return psql(
        ADMIN_DATABASE,
        "SELECT 'ALTER DATABASE "
            + source
            + " RENAME TO "
            + kept
            + "' WHERE EXISTS (SELECT 1 FROM pg_database WHERE datname = '"
            + source
            + "')\n\\gexec\n"
            + "SELECT 'ALTER DATABASE "
            + kept
            + " WITH ALLOW_CONNECTIONS false' WHERE EXISTS (SELECT 1 FROM pg_database WHERE datname"
            + " = '"
            + kept
            + "')\n\\gexec");
  }

  /**
   * {@link #rename}'s way back (qits-2249): a switched copy whose deployment then failed puts its
   * kept source back under its own name — {@code ALLOW_CONNECTIONS true} on the kept copy, the copy
   * renamed back while the source name is free, and {@code CONNECT} granted to its role again — so
   * the original service starts on exactly the data it left. Idempotent over every state the switch
   * can have left: a kept copy renamed back once is not renamed again, and a source the switch never
   * managed to rename aside is simply re-opened.
   */
  static String restoreKept(String kept, String source) {
    String k = id(kept);
    String s = id(source);
    return psql(
        ADMIN_DATABASE,
        "SELECT 'ALTER DATABASE "
            + k
            + " WITH ALLOW_CONNECTIONS true' WHERE EXISTS (SELECT 1 FROM pg_database WHERE datname"
            + " = '"
            + k
            + "')\n\\gexec\n"
            + "SELECT 'ALTER DATABASE "
            + k
            + " RENAME TO "
            + s
            + "' WHERE EXISTS (SELECT 1 FROM pg_database WHERE datname = '"
            + k
            + "') AND NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = '"
            + s
            + "')\n\\gexec\n"
            + reopenSql(source, source));
  }

  /**
   * A move between two names on ONE server (qits-2193, the owner's ruling of 2026-11-14): the
   * database and its role renamed in place, instant and needing no space — never a dump and a
   * restore of a store the shared postgres has to hold twice. The same script is the way back, with
   * the names swapped.
   *
   * <p>One admin session: the source's backends terminated once more (the service is already at
   * zero and the source fenced; {@code ALTER DATABASE … RENAME} refuses while anyone else is
   * connected, and waits a few seconds for a terminated backend to go); with {@code dropLeftover},
   * what an earlier ABORTED attempt of this move left under the target name — only while the source
   * is still there, so this can never drop the data itself; then ONE transaction: {@link
   * #renameInPlaceSql}, the role's password set again (postgres CLEARS an MD5 password on a role
   * rename, the role name being its salt; SCRAM survives, and setting it again is harmless), and
   * {@code CONNECT} granted back under the new name. Either all of it commits or none of it does.
   *
   * <p><b>Idempotent, and strict.</b> A database already renamed is not renamed again — the rest
   * still runs, so a repeat after a lost answer re-opens it; both names present is an error (the
   * rename refuses an existing name), and neither present is an error (the {@code GRANT} names a
   * database that does not exist).
   *
   * @param password whether {@value #ROLE_PASSWORD} carries the role's password to set again
   */
  static String renameInPlace(String from, String to, boolean dropLeftover, boolean password) {
    String source = id(from);
    String target = id(to);
    StringBuilder sql = new StringBuilder();
    if (password) {
      sql.append("\\getenv role_password ").append(ROLE_PASSWORD).append('\n');
    }
    sql.append("SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE datname = '")
        .append(source)
        .append("' AND pid <> pg_backend_pid();\n");
    if (dropLeftover) {
      String sourceThere = "EXISTS (SELECT 1 FROM pg_database WHERE datname = '" + source + "')";
      sql.append("SELECT 'DROP DATABASE ")
          .append(target)
          .append(" WITH (FORCE)' WHERE ")
          .append(sourceThere)
          .append(" AND EXISTS (SELECT 1 FROM pg_database WHERE datname = '")
          .append(target)
          .append("')\n\\gexec\n")
          .append("SELECT 'DROP ROLE ")
          .append(target)
          .append("' WHERE ")
          .append(sourceThere)
          .append(" AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '")
          .append(source)
          .append("') AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '")
          .append(target)
          .append("')\n\\gexec\n");
    }
    sql.append("BEGIN;\n").append(renameInPlaceSql(from, to)).append('\n');
    if (password) {
      sql.append("ALTER ROLE ").append(target).append(" WITH LOGIN PASSWORD :'role_password';\n");
    }
    sql.append(reopenSql(to, to)).append("\nCOMMIT;");
    return psql(ADMIN_DATABASE, sql.toString());
  }

  /**
   * {@link #renameInPlace}'s rename, plain SQL — what the real-postgres test runs over JDBC: while
   * the source database exists, it is renamed to the target and its role (the role IS the database
   * name) with it, in the same statement block; once it is gone, nothing.
   */
  static String renameInPlaceSql(String from, String to) {
    String source = id(from);
    String target = id(to);
    return "DO $qits$\nBEGIN\n"
        + "  IF EXISTS (SELECT 1 FROM pg_database WHERE datname = '"
        + source
        + "') THEN\n"
        + "    ALTER DATABASE "
        + source
        + " RENAME TO "
        + target
        + ";\n"
        + "    ALTER ROLE "
        + source
        + " RENAME TO "
        + target
        + ";\n"
        + "  END IF;\nEND\n$qits$;";
  }

  /** Drop a database, connections and all, and optionally the role that owned it. */
  static String drop(String database, String role) {
    String sql = "DROP DATABASE IF EXISTS " + id(database) + " WITH (FORCE);";
    if (role != null) {
      sql += "\nDROP ROLE IF EXISTS " + id(role) + ";";
    }
    return psql(ADMIN_DATABASE, sql);
  }

  /** {@code <database>_moved_<yyyyMMddHHmm>}, the database part cut so the whole stays in 63. */
  static String keptName(String database, Instant at) {
    String suffix = "_moved_" + KEPT_STAMP.format(at);
    String base = id(database);
    if (base.length() + suffix.length() > NAME_MAX) {
      base = base.substring(0, NAME_MAX - suffix.length());
    }
    return id(base + suffix);
  }

  /**
   * The manifest between the markers: {@code <schema>.<table>=<rows>} per line, sorted by table.
   * Null when the markers are missing — a script that never reached its last step.
   */
  static Map<String, Long> manifest(String output) {
    Map<String, Long> counts = new TreeMap<>();
    boolean inside = false;
    boolean closed = false;
    for (String line : lines(output)) {
      if (line.equals(MANIFEST_BEGIN)) {
        inside = true;
        counts.clear();
        continue;
      }
      if (line.equals(MANIFEST_END)) {
        closed = inside;
        inside = false;
        continue;
      }
      if (inside && !line.isEmpty()) {
        int eq = line.lastIndexOf('=');
        if (eq <= 0) {
          return null;
        }
        try {
          counts.put(line.substring(0, eq), Long.parseLong(line.substring(eq + 1).strip()));
        } catch (NumberFormatException unreadable) {
          return null;
        }
      }
    }
    return closed ? counts : null;
  }

  /**
   * The first table the two manifests disagree on, by name — a table on one side only, or two
   * counts — as a sentence; null when they agree.
   */
  static String firstDifference(Map<String, Long> source, Map<String, Long> target) {
    TreeMap<String, Boolean> tables = new TreeMap<>();
    source.keySet().forEach(t -> tables.put(t, true));
    target.keySet().forEach(t -> tables.put(t, true));
    for (String table : tables.keySet()) {
      Long before = source.get(table);
      Long after = target.get(table);
      if (before == null) {
        return table + " is on the target only (" + after + " rows)";
      }
      if (after == null) {
        return table + " is missing on the target (" + before + " rows on the source)";
      }
      if (!before.equals(after)) {
        return table + " has " + before + " rows on the source and " + after + " on the target";
      }
    }
    return null;
  }

  /** The sum of a manifest. */
  static long total(Map<String, Long> counts) {
    return counts.values().stream().mapToLong(Long::longValue).sum();
  }

  /**
   * An exact {@code count(*)} of every table outside {@code pg_catalog} and {@code
   * information_schema}, one generated statement per table run by {@code \gexec}; each prints
   * {@code <schema>.<table>=<rows>}.
   */
  private static final String COUNTS =
      "SELECT format('SELECT %L || %L || count(*) FROM %I.%I;', n.nspname || '.' || c.relname, '=',"
          + " n.nspname, c.relname)\n"
          + "  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace\n"
          + " WHERE c.relkind IN ('r', 'p')\n"
          + "   AND n.nspname NOT IN ('pg_catalog', 'information_schema')\n"
          + "   AND n.nspname NOT LIKE 'pg\\_temp\\_%' AND n.nspname NOT LIKE 'pg\\_toast%'\n"
          + " ORDER BY n.nspname, c.relname\n"
          + "\\gexec";

  private static String printManifest(String manifest) {
    return "echo " + MANIFEST_BEGIN + "\ncat " + manifest + "\necho " + MANIFEST_END + "\n";
  }

  private static String id(String identifier) {
    return PdIdentifiers.requireDatabaseName(identifier);
  }

  private static String[] lines(String output) {
    return (output == null ? "" : output).replace("\r", "").lines().map(String::strip).toArray(String[]::new);
  }
}
