package covia.venue;

import java.io.IOException;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.store.AStore;
import convex.core.util.JSONUtils;
import convex.etch.EtchStore;

public class Venue {

	protected final AStore store;
	
	protected ACell lattice;
	
	
	public Venue() throws IOException {
		this(EtchStore.createTemp());
	}


	public Venue(EtchStore store) {
		this.store=store;
	}


	public Hash storeAsset(ACell meta, ACell content) {
		AString metaString=JSONUtils.toJSONString(meta);
		
		return storeAsset(metaString,content);
	}
	
	public Hash storeAsset(AString meta, ACell content) {
		Hash id=meta.toBlob().getContentHash();
		AMap<ABlob,AVector<?>> assets=getAssets();
		assets=assets.assoc(id, meta); // TODO: asset record design?		
		return id;
	}


	public AMap<ABlob, AVector<?>> getAssets() {
		// TODO Auto-generated method stub
		return Index.none();
	}


	public static Venue createTemp() {
		try {
			return new Venue(EtchStore.createTemp());
		} catch (IOException e) {
			throw new Error(e);
		}
	}
	
	
}
