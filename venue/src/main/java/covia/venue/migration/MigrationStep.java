package covia.venue.migration;

import convex.core.data.ACell;

/**
 * One step of a stored schema, from one version to the next: Covia's venue
 * value ({@link VenueMigrations}) or an adapter's state
 * ({@link covia.adapter.AAdapter#stateSteps()}). The steps of a schema are an
 * ordered list, and step {@code i} takes state at version {@code i} to version
 * {@code i + 1}.
 *
 * <p>A step is a pure function of its argument: no I/O, no engine calls, no
 * clock, no configuration. It may run again on a retried transaction, or have
 * its result discarded. It returns the same cell when nothing changes, and
 * throws for a shape it does not recognise, so the migration fails and the
 * stored state stays as it was.</p>
 */
@FunctionalInterface
public interface MigrationStep {
	/**
	 * @param state the state at this step's version; an adapter's is never null
	 * @return the state at the next version
	 */
	ACell apply(ACell state);
}
