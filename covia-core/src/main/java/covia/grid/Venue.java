package covia.grid;

import covia.grid.client.CoviaHTTP;

/**
 * Class representing a Grid Venue
 */
public class Venue {

	private CoviaHTTP client;

	public Venue(CoviaHTTP coviaHTTP) {
		this.client=coviaHTTP;
	}

	public static Venue connect(String id) {
		return new Venue(CoviaHTTP.create(id));
	}

}
