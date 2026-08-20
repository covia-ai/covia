package covia.venue;

import java.util.Objects;
import java.util.regex.Pattern;

import convex.core.data.ACell;
import convex.core.data.AString;
import covia.adapter.CoviaAdapter;

/**
 * Venue-private durable storage bound to one adapter.
 *
 * <p>The canonical root is {@code <venue-did>/w/adapters/<adapter>/}. This is
 * intentionally an ordinary workspace subtree of the venue principal: normal
 * workspace replication and timestamp rules apply, while callers do not gain
 * access merely because a record is keyed by their DID. An adapter may map
 * user-specific preferences or instances below {@code users/<did>/}, but owns
 * their schema and exposes them through its operations.</p>
 */
public final class AdapterWorkspace {
	private static final Pattern ADAPTER_NAME=Pattern.compile("[a-z][a-z0-9-]*");
	private static final String ROOT="w/adapters/";

	private final Engine engine;
	private final String adapterName;
	private final String rootPath;

	AdapterWorkspace(Engine engine,String adapterName) {
		this.engine=Objects.requireNonNull(engine,"engine");
		if(adapterName==null||!ADAPTER_NAME.matcher(adapterName).matches()) {
			throw new IllegalArgumentException("Adapter workspace name must match [a-z][a-z0-9-]*: "+adapterName);
		}
		this.adapterName=adapterName;
		this.rootPath=ROOT+adapterName;
	}

	public String adapterName(){return adapterName;}
	public String rootPath(){return rootPath;}

	/** Canonical {@code w/adapters/<adapter>/<relative>} path for diagnostics and tests. */
	public String path(String relative){
		String r=validateRelative(relative,true);
		return r.isEmpty()?rootPath:rootPath+"/"+r;
	}

	/** Relative location convention for adapter-owned records associated with a user. */
	public String userPath(AString userDID,String relative){
		if(userDID==null||!userDID.toString().startsWith("did:")||userDID.toString().contains("/")) {
			throw new IllegalArgumentException("Adapter workspace user must be a bare DID: "+userDID);
		}
		String suffix=validateRelative(relative,true);
		String base="users/"+userDID;
		return suffix.isEmpty()?base:base+"/"+suffix;
	}

	public ACell read(String relative){
		return venueUser().readInternalPath(keys(path(relative)));
	}

	public void write(String relative,ACell value){
		Objects.requireNonNull(value,"Adapter workspace values must be non-null; use delete()");
		validateRelative(relative,false);
		venueUser().writeInternalPath(keys(path(relative)),value);
	}

	public boolean delete(String relative){
		validateRelative(relative,false);
		return venueUser().deleteInternalPath(keys(path(relative)));
	}

	private User venueUser(){return engine.getVenueState().users().ensure(engine.getDIDString());}
	private static ACell[] keys(String path){return CoviaAdapter.parseStringPath(path);}

	private static String validateRelative(String relative,boolean allowEmpty){
		if(relative==null)throw new IllegalArgumentException("Adapter workspace path is required");
		if(relative.isEmpty()){if(allowEmpty)return relative;throw new IllegalArgumentException("Adapter workspace mutation path must not be empty");}
		if(relative.startsWith("/")||relative.endsWith("/")||relative.indexOf('\\')>=0||relative.length()>2048)throw new IllegalArgumentException("Invalid adapter workspace relative path: "+relative);
		for(String segment:relative.split("/",-1))if(segment.isEmpty()||segment.equals(".")||segment.equals("..")||segment.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid adapter workspace path segment in: "+relative);
		return relative;
	}
}
