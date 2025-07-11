package covia.venue;

import java.io.IOException;

import convex.core.data.Hash;
import convex.core.util.Utils;
import covia.grid.Assets;

public class TestOps {
	
	public static Hash RANDOM=null;
	public static Hash ECHO=null;
	public static Hash ERROR=null;

	static {
		 try {
			RANDOM=Assets.calcID(Utils.readResourceAsString("/asset-examples/randomop.json"));
			ECHO=Assets.calcID(Utils.readResourceAsString("/asset-examples/echoop.json"));
			ERROR=Assets.calcID(Utils.readResourceAsString("/asset-examples/failop.json"));
		 } catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		 }
	}
}
