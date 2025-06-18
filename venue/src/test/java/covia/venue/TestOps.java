package covia.venue;

import java.io.IOException;

import convex.core.data.Hash;
import convex.core.util.Utils;
import covia.client.Asset;

public class TestOps {
	
	static Hash RANDOM=null;
	static Hash ECHO=null;

	static {
		 try {
			RANDOM=Asset.calcID(Utils.readResourceAsString("/asset-examples/randomop.json"));
			ECHO=Asset.calcID(Utils.readResourceAsString("/asset-examples/echoop.json"));
		 } catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		 }
	}
}
