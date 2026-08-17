package covia.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Marks a test (class or method) that reaches an external service — the real
 * network, an Ollama server, a live peer — so it is flaky or
 * environment-dependent.
 *
 * <p><b>Opt-in.</b> Such tests run only when {@code -Dcovia.tests.integration=true}
 * is set; otherwise JUnit reports them skipped with the reason below. This
 * replaces the old {@code excludedGroups} denylist: a test declares its own
 * gate, so opening the file shows both why it is off and how to turn it on,
 * and there is no build-file list to keep in sync. Selecting the test by name
 * ({@code -Dtest=…}) still requires the flag — the gate is on the test, not on
 * how it was selected.</p>
 *
 * <p>Still carries {@code @Tag("integration")} for any tag-based tooling.</p>
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("integration")
@EnabledIfSystemProperty(named = "covia.tests.integration", matches = "true",
	disabledReason = "external-service test; enable with -Dcovia.tests.integration=true")
public @interface IntegrationTest {
}
