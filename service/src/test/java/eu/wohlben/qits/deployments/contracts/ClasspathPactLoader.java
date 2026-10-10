package eu.wohlben.qits.deployments.contracts;

import au.com.dius.pact.core.model.DefaultPactReader;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.UrlSource;
import au.com.dius.pact.core.support.json.JsonParser;
import au.com.dius.pact.provider.junitsupport.loader.PactLoader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * <b>Every {@code pacts/<consumer>_<provider>.json} resource on the test classpath</b> — inside dependency
 * jars (the pinned consumer pact jars, which is the point) as well as in plain directories.
 *
 * <p>It asks the class loader for every {@code pacts/} directory, then lists each one: a {@code jar:}
 * URL by walking the jar's entries, a {@code file:} URL by listing the directory.
 *
 * <p><b>Finding nothing is allowed until the first consumer pins.</b> While {@link #REQUIRED} is
 * false, an empty classpath verifies nothing and says so in the log. When the first consumer's pact
 * jar ({@code <consumer>-pacts-qits-deployments-service}) is pinned in the pom, set {@link #REQUIRED} to
 * true and drop {@code @IgnoreNoPactsToVerify} from {@code ConsumerPactVerificationTest}: from then
 * on finding nothing is an error, because a provider whose pinned pacts vanished has lost its
 * dependency, and a green run would claim the opposite.
 */
public class ClasspathPactLoader implements PactLoader {

  static final String DIRECTORY = "pacts/";

  /** Whether an empty classpath fails the build. False until the first consumer pact is pinned. */
  static final boolean REQUIRED = false;

  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(ClasspathPactLoader.class);

  @Override
  public String description() {
    return "classpath:" + DIRECTORY + "*_<provider>.json";
  }

  @Override
  public au.com.dius.pact.core.model.PactSource getPactSource() {
    return null;
  }

  @Override
  public List<Pact> load(String providerName) {
    String suffix = "_" + providerName + ".json";
    TreeMap<String, URL> found = new TreeMap<>();
    try {
      ClassLoader loader = getClass().getClassLoader();
      for (String name : List.of("pacts", DIRECTORY)) {
        Enumeration<URL> dirs = loader.getResources(name);
        while (dirs.hasMoreElements()) {
          list(dirs.nextElement(), suffix, found);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (found.isEmpty() && !REQUIRED) {
      LOG.warnf(
          "No consumer pact against %s on the test classpath (%s*%s): nothing to verify yet",
          providerName, DIRECTORY, suffix);
      return List.of();
    }
    if (found.isEmpty()) {
      throw new IllegalStateException(
          "No consumer pact against "
              + providerName
              + " on the test classpath (looked for "
              + DIRECTORY
              + "*"
              + suffix
              + ") — the pinned qits-*-pacts-"
              + providerName
              + " test dependencies are missing");
    }
    List<Pact> pacts = new ArrayList<>();
    for (URL url : found.values()) {
      try (InputStream in = url.openStream()) {
        String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        pacts.add(
            DefaultPactReader.INSTANCE.pactFromJson(
                JsonParser.parseString(json).asObject(), new UrlSource(url.toString())));
      } catch (IOException e) {
        throw new UncheckedIOException("Reading the pact " + url, e);
      }
    }
    return Collections.unmodifiableList(pacts);
  }

  /** Adds every matching file under one {@code pacts/} directory URL, keyed by its URL. */
  private static void list(URL dir, String suffix, TreeMap<String, URL> found)
      throws IOException {
    switch (dir.getProtocol()) {
      case "jar" -> {
        JarURLConnection connection = (JarURLConnection) dir.openConnection();
        connection.setUseCaches(false);
        String jar = connection.getJarFileURL().toString();
        try (JarFile file = new JarFile(Path.of(toUri(connection.getJarFileURL())).toFile())) {
          Enumeration<JarEntry> entries = file.entries();
          while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (matches(name, suffix)) {
              found.put("jar:" + jar + "!/" + name, new URL("jar:" + jar + "!/" + name));
            }
          }
        }
      }
      case "file" -> {
        Path path = Path.of(toUri(dir));
        try (Stream<Path> files = Files.list(path)) {
          for (Path file : files.toList()) {
            if (matches(DIRECTORY + file.getFileName(), suffix)) {
              found.put(file.toUri().toString(), file.toUri().toURL());
            }
          }
        }
      }
      default -> throw new IllegalStateException("Cannot list pacts at " + dir);
    }
  }

  private static boolean matches(String entry, String suffix) {
    return entry.startsWith(DIRECTORY)
        && entry.indexOf('/', DIRECTORY.length()) < 0
        && entry.endsWith(suffix)
        && entry.length() > DIRECTORY.length() + suffix.length();
  }

  private static java.net.URI toUri(URL url) {
    try {
      return url.toURI();
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(url.toString(), e);
    }
  }
}
