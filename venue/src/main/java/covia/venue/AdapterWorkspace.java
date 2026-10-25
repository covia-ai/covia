package covia.venue;

import covia.lattice.WorkspaceRoot;
import java.util.Objects;
import java.util.regex.Pattern;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.exception.AuthException;
import covia.lattice.Covia;

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
	public static final String USER_RECORD_REQUIRED="Write individual adapter user records";

	private final VenueState venue;
	private final AString venueDID;
	private final AString owner;
	private final ACell generation;
	private final String adapterName;
	private final String rootPath;

	AdapterWorkspace(VenueState venue,AString venueDID,String adapterName) {
		this(venue,venueDID,adapterName,null,null);
	}

	private AdapterWorkspace(VenueState venue,AString venueDID,String adapterName,AString owner,ACell generation) {
		this.venue=Objects.requireNonNull(venue,"venue");
		this.venueDID=Objects.requireNonNull(venueDID,"venueDID");
		this.owner=owner;
		this.generation=generation;
		if(adapterName==null||!ADAPTER_NAME.matcher(adapterName).matches()) {
			throw new IllegalArgumentException("Adapter workspace name must match [a-z][a-z0-9-]*: "+adapterName);
		}
		this.adapterName=adapterName;
		this.rootPath=ROOT+adapterName;
	}

	public String adapterName(){return adapterName;}
	public String rootPath(){return rootPath;}

	/** Reject a queued callback whose worker belongs to a deleted/replaced account. */
	public void requireCurrentUser(){requireCurrent(venue);}

	/** Bind a worker's storage to this account lifecycle, including config-owned paths. */
	public AdapterWorkspace forUser(AString did) {
		userPath(did,""); // validate the association key
		ACell record=RT.getIn(venue.get(),Covia.USER_DATA,did);
		if(RT.getIn(record,Covia.K_DELETED)!=null)throw new AuthException(Users.ACCOUNT_DELETED);
		return new AdapterWorkspace(venue,venueDID,adapterName,did,RT.getIn(record,Users.GENERATION));
	}

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
		ACell[] path=keys(path(relative));
		ACell[] result=new ACell[1];
		venue.transact(draft -> {
			requireCurrent(draft);
			User user=draft.users().get(venueDID);
			result[0]=user==null?null:user.readInternalPath(path);
		});
		return result[0];
	}

	public void write(String relative,ACell value){
		Objects.requireNonNull(value,"Adapter workspace values must be non-null; use delete()");
		validateRelative(relative,false);
		venue.transact(draft -> {
			requireCurrent(draft);
			// A caller using the conventional user path still gets an atomic
			// tombstone check; long-lived workers must additionally use forUser.
			if(relative.startsWith("users/")) {
				String did=relative.substring(6).split("/",2)[0];
				draft.users().requireNotDeleted(Strings.create(did));
			}
			if(relative.equals("users"))throw new IllegalArgumentException(USER_RECORD_REQUIRED);
			draft.users().ensure(venueDID).writeInternalPath(keys(path(relative)),value);
		});
	}

	public boolean delete(String relative){
		validateRelative(relative,false);
		boolean[] removed=new boolean[1];
		venue.transact(draft -> {
			requireCurrent(draft);
			User user=draft.users().get(venueDID);
			removed[0]=user!=null && user.deleteInternalPath(keys(path(relative)));
		});
		return removed[0];
	}

	private void requireCurrent(VenueState draft){
		if(owner==null)return;
		ACell record=RT.getIn(draft.get(),Covia.USER_DATA,owner);
		Users.requireCurrent(record,generation);
	}
	private static ACell[] keys(String path){return WorkspaceRoot.parseStringPath(path);}

	private static String validateRelative(String relative,boolean allowEmpty){
		if(relative==null)throw new IllegalArgumentException("Adapter workspace path is required");
		if(relative.isEmpty()){if(allowEmpty)return relative;throw new IllegalArgumentException("Adapter workspace mutation path must not be empty");}
		if(relative.startsWith("/")||relative.endsWith("/")||relative.indexOf('\\')>=0||relative.length()>2048)throw new IllegalArgumentException("Invalid adapter workspace relative path: "+relative);
		for(String segment:relative.split("/",-1))if(segment.isEmpty()||segment.equals(".")||segment.equals("..")||segment.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid adapter workspace path segment in: "+relative);
		return relative;
	}
}
