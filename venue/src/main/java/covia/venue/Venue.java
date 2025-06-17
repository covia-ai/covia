package covia.venue;

import java.io.IOException;

import convex.core.crypto.Hashing;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.core.util.JSONUtils;
import convex.core.util.Utils;
import convex.etch.EtchStore;

public class Venue {

	public static final Keyword COVIA_KEY = Keyword.intern("covia");
	public static final Keyword ASSETS_KEY = Keyword.intern("assets");


	protected final AStore store;
	
	
	protected ACell lattice=Maps.create(COVIA_KEY, Maps.create(ASSETS_KEY,Index.EMPTY));
	
	
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
	
	public synchronized Hash storeAsset(AString meta, ACell content) {
		Hash id=Hashing.sha256(meta.toBlob().getBytes());
		AMap<ABlob,AVector<?>> assets=getAssets();
		setAssets(assets.assoc(id, assetRecord(meta,content))); // TODO: asset record design?		
		return id;
	}


	private AVector<ACell> assetRecord(AString meta, ACell content) {
		return Vectors.create(meta,content);
	}


	private void setAssets(AMap<ABlob, AVector<?>> assets) {
		lattice=RT.assocIn(lattice, assets, COVIA_KEY, ASSETS_KEY);
	}


	public AMap<ABlob, AVector<?>> getAssets() {
		// Get assets from lattice
		return RT.ensureMap(RT.getIn(lattice, COVIA_KEY,ASSETS_KEY));
	}


	public static Venue createTemp() {
		try {
			return new Venue(EtchStore.createTemp());
		} catch (IOException e) {
			throw new Error(e);
		}
	}


	public AString getMetadata(Hash assetID) {
		AVector<?> arec=getAssets().get(assetID);
		if (arec==null) return null;
		return RT.ensureString(arec.get(0));
	}


	public static void addDemoAssets(Venue venue) {
		String BASE="/asset-examples/";
		try {
			venue.storeAsset(Strings.create(Utils.readResourceAsString(BASE+"empty.json")),null);
			venue.storeAsset(Strings.create(Utils.readResourceAsString(BASE+"randomop.json")),null);
		} catch (IOException e) {
			throw new Error(e);
		}
	}
	
	
}
