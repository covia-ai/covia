package covia.venue.migration;

import java.util.List;

import covia.venue.SecretStore;

/**
 * Covia's venue schema steps, in order: step {@code i} takes a venue value at
 * version {@code i} to version {@code i + 1}, so the current venue version is
 * the number of steps. A release moves the venue one version at most, its
 * step made of that release's changes as parts ({@link MigrationStep#of}). A
 * schema change before the release adds a part to its step; a released step
 * never changes, and the next change starts a new step at the end.
 */
public final class VenueMigrations {
	private VenueMigrations() {}

	/** The current venue version: the number of {@link #steps}. */
	public static final long VERSION = 2;

	/**
	 * The steps, built with the venue's secret store keys, which a part that
	 * carries secrets over reads them with.
	 *
	 * @param keys The venue's secret store keys ({@link SecretStore#keys})
	 */
	public static List<MigrationStep> steps(SecretStore.Keys keys) {
		return List.of(
			new UserDeletionsRegion(),		// 0 → 1
			MigrationStep.of(				// 1 → 2
				new CurrentJobRecords(),
				new CallerAndOwner(),
				new JobEntries(keys),
				new DelegationEntries()));
	}
}
