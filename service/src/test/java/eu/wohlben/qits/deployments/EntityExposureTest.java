package eu.wohlben.qits.deployments;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import eu.wohlben.qits.archrules.EntityExposure;
import eu.wohlben.qits.archrules.EntityExposureRules;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * qits-1945's entity-exposure rules over this service: every {@code @Entity} reaches a JAX-RS
 * operation through a DTO, or says why not with {@code @Technical} or {@code @Projection}, or is
 * a known gap naming the ticket that will close it. {@code map} keeps the analysis as {@code
 * docs/entities.json}; rerun with {@code -Dgolden.update=true} to rewrite it.
 */
@AnalyzeClasses(packages = "eu.wohlben.qits.deployments", importOptions = ImportOption.DoNotIncludeTests.class)
class EntityExposureTest {

  /** PdDecommission: the run history is written by the decommission door and read by none. */
  static final Set<String> KNOWN_GAPS = Set.of("PdDecommission qits-1956");

  @ArchTest
  static final ArchRule EXPOSED = EntityExposureRules.everyDomainEntityIsExposed(KNOWN_GAPS);

  @ArchTest static final ArchRule GAPS = EntityExposureRules.knownGapsAreReal(KNOWN_GAPS);

  @ArchTest static final ArchRule MARKERS = EntityExposureRules.markersAreWellFormed;

  @ArchTest
  static void map(JavaClasses classes) throws IOException {
    EntityExposure.verify(classes, "qits-deployments", KNOWN_GAPS, Path.of("../docs/entities.json"));
  }
}
