package covia.venue.migration;

import java.util.List;

import covia.venue.SecretStore;

/**
 * Covia's venue schema steps, in order: step {@code i} takes a venue value at
 * version {@code i} to version {@code i + 1}, so the current venue version is
 * the number of steps. A schema change adds its step at the end; a released
 * step never changes.
 */
public final class VenueMigrations {
	private VenueMigrations() {}

	/** The current venue version: the number of {@link #steps}. */
	public static final long VERSION = 3;

	/**
	 * The steps, built with the venue's secret store keys, which a step that
	 * carries secrets over reads them with.
	 *
	 * @param keys The venue's secret store keys ({@link SecretStore#keys})
	 */
	public static List<MigrationStep> steps(SecretStore.Keys keys) {
		return List.of(
			new UserDeletionsRegion(),	// 0 → 1
			new CallerAndOwner(),		// 1 → 2
			new JobEntries(keys));		// 2 → 3
	}
}
