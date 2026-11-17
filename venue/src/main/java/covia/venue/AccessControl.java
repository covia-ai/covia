package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.api.Abilities;

/** Shared resource checks for Job APIs and scheduled events. */
public class AccessControl {

	private final Engine engine;

	AccessControl(Engine engine) {
		this.engine = engine;
	}

	/** Whether the caller may read the Job whose record {@code jobData} is kept in {@code account}. */
	public boolean canAccessJob(RequestContext ctx, AString account, AMap<AString, ACell> jobData) {
		return canAccessJob(ctx, account, jobData, Abilities.JOB_READ);
	}

	/**
	 * Job abilities are independent of raw lattice CRUD. The account a job is
	 * kept in supplies ordinary standing, but never bypasses a scope or access
	 * token. Being the record's caller is not a grant. Other principals need a
	 * valid delegation.
	 *
	 * @param account the account the job's record is kept in
	 */
	public boolean canAccessJob(RequestContext ctx, AString account, AMap<AString, ACell> jobData, AString ability) {
		if (ctx == null || jobData == null) return false;
		AString owner = account;
		if (owner == null || ctx.getUserDID() == null) return false;
		// An incomplete record has no delegable resource; only its unscoped owner.
		if (!(jobData.get(Fields.ID) instanceof Blob id))
			return owner.equals(ctx.getUserDID()) && isOwnerOutright(ctx) && !ctx.isNarrowed();
		return reaches(ctx, owner, JobManager.resource(owner, id), ability, Strings.create(engine.config().getBaseUrl()));
	}

	/**
	 * Whether the request may use {@code ability} on the scheduled event at
	 * {@code resource} ({@code <account>/x/schedule/<id>}) in {@code account}:
	 * {@code crud/read} inspects and lists it, {@link Abilities#SCHEDULE_MANAGE}
	 * cancels or triggers it. Having scheduled an event grants nothing, as
	 * having started a job does not.
	 */
	public boolean canAccessEvent(RequestContext ctx, AString account, AString resource, AString ability) {
		if (ctx == null || account == null || ctx.getUserDID() == null) return false;
		return reaches(ctx, account, resource, ability, null);
	}

	/**
	 * Whether the request's authority covers {@code ability} on {@code resource}
	 * in {@code account}, as for any resource: an account's whole authority
	 * covers what it holds, the caps of a token, callable or agent decide within
	 * it, and another account's resource needs a grant.
	 */
	private boolean reaches(RequestContext ctx, AString account, AString resource, AString ability, AString destination) {
		RequestContext authority = engine.authorityFrame(ctx);
		if (!account.equals(authority.getUserDID()) && !account.equals(authority.getAuthorityDID())
				&& !engine.crossUserAllows(ctx, resource, ability, destination)) return false;
		return engine.authorityCovers(ctx, resource, ability, destination);
	}

	/**
	 * Whether the request acts with its namespace owner's whole authority: the
	 * authority it acts with ({@link Engine#authorityFrame}) is that owner's,
	 * unscoped. An agent whose authority comes from another account is never
	 * its owner outright.
	 */
	private boolean isOwnerOutright(RequestContext ctx) {
		RequestContext authority = engine.authorityFrame(ctx);
		return authority.getCaps() == null && ctx.getUserDID().equals(authority.getUserDID());
	}

}
