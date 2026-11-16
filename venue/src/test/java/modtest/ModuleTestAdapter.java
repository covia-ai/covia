package modtest;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.exception.ConfigurationException;
import covia.venue.RequestContext;

/**
 * Minimal adapter packaged into a jar AT TEST RUNTIME by
 * {@link covia.venue.ModuleLoaderTest} to exercise the venue module loader
 * end-to-end. Deliberately OUTSIDE the covia.* package so the
 * split-delegation classloader loads it child-first from the module jar
 * (shared covia.* / convex.* prefixes stay parent-first). Its {@code label}
 * setting, from {@code adapters.modtest}, shows in its description.
 */
public class ModuleTestAdapter extends AAdapter {
	public static final String LABEL_NOT_STRING = "adapters.modtest.label must be a string";
	public static final String UNKNOWN_SETTING = "Unknown modtest setting: ";

	private String label = "unconfigured";

	@Override
	public void configure(AMap<AString, ACell> config, boolean strict) {
		if (strict) {
			for (AString key : config.keySet()) {
				if (!"label".equals(key.toString()) && !"enabled".equals(key.toString())) {
					throw new ConfigurationException(UNKNOWN_SETTING + key);
				}
			}
		}
		ACell rawLabel = config.get(Strings.create("label"));
		if (rawLabel == null) return;
		AString configured = RT.ensureString(rawLabel);
		if (configured == null) throw new ConfigurationException(LABEL_NOT_STRING);
		label = configured.toString();
	}

	@Override
	public String getName() {
		return "modtest";
	}

	@Override
	public String getDescription() {
		return "Test adapter loaded from a venue module jar: " + label;
	}

	@Override
	protected void installAssets() {
		// This resource exists ONLY inside the module jar — proves adapter
		// resources resolve via the module classloader, not the venue's.
		installAsset("modtest/echo", "/modtest/echo.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		return CompletableFuture.completedFuture(input);
	}
}
