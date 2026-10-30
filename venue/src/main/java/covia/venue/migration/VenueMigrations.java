package covia.venue.migration;

import java.util.List;

/**
 * Covia's venue schema steps, in order: step {@code i} takes a venue value at
 * version {@code i} to version {@code i + 1}, so the current venue version is
 * the number of steps. A schema change adds its step at the end; a released
 * step never changes.
 */
public final class VenueMigrations {
	private VenueMigrations() {}

	public static final List<MigrationStep> STEPS = List.of(
		new UserDeletionsRegion());	// 0 → 1
}
