package covia.grid.impl;

import convex.core.data.AString;
import covia.grid.AContent;
import covia.grid.Asset;

/**
 * Base class for local assets
 */
public abstract class LocalArtifact extends Asset {

	private AContent content;

	protected LocalArtifact(AString metadata, AContent content) {
		super(metadata);
		this.content=content;
	}

	@Override
	public AContent getContent() {
		return content;
	}
}
