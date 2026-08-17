package covia.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Marks a crash/restart durability test (class or method): it forks real JVMs
 * and relaunches venues to prove state survives a hard or soft kill, so it is
 * slow (seconds each, dominated by the hard-kill test) and not part of the
 * fast inner loop.
 *
 * <p><b>Opt-in.</b> Such tests run only when {@code -Dcovia.tests.durability=true}
 * is set; otherwise JUnit reports them skipped with the reason below. CI opts
 * in on every push. This replaces the old {@code excludedGroups} denylist: the
 * gate lives on the test itself, so it is off by default whether selected by
 * group or by name ({@code -Dtest=…}), and there is no build-file list to keep
 * in sync.</p>
 *
 * <p>Still carries {@code @Tag("durability")} for any tag-based tooling.</p>
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("durability")
@EnabledIfSystemProperty(named = "covia.tests.durability", matches = "true",
	disabledReason = "crash/restart durability test (forks JVMs); enable with -Dcovia.tests.durability=true")
public @interface DurabilityTest {
}
