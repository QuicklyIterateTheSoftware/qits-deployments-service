package eu.wohlben.qits.deployments;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import eu.wohlben.qits.archrules.CausationRowRules;
import eu.wohlben.qits.archrules.HttpClientRules;

/**
 * The platform's shared ArchUnit rules over this component's classes. Today that is the
 * causation-row completeness guard: every {@code @Entity} either implements {@code CausedRow} — and
 * then lists {@code CausationStamp} in its {@code @EntityListeners} — or declares {@code @Uncaused}
 * with its reason in the javadoc. A new entity that skips the decision fails this build naming the
 * class, instead of leaving a silent hole in the trace. The rule set lives in qits-arch-rules
 * (qits-integrations-quarkus); a new set added there arrives here as one more {@code @ArchTest}
 * line.
 *
 * <p><b>It lives in {@code service/} and not in a domain module, and the reason is this repo's
 * layering.</b> The entities are split across two jars — {@code environments} owns three, {@code
 * deployments} owns two — and {@code service} is the only module whose classpath carries both, plus
 * whatever it ever adds itself. A copy in each domain module would judge {@code deployments} twice
 * (it depends on {@code environments}) and judge {@code service} never. The package it analyzes is
 * the component's whole root, so an entity added anywhere under it is covered by the test that is
 * already here.
 *
 * <p>The rules judge types by fully-qualified name and depend on neither qits-eventstream nor
 * jakarta.persistence, which is why this test needs nothing but the one test-scope dependency.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.deployments",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchRulesTest {

  @ArchTest static final ArchTests CAUSATION = ArchTests.in(CausationRowRules.class);

  @ArchTest static final ArchTests HTTP_CLIENTS = ArchTests.in(HttpClientRules.class);
}
