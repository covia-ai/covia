package covia.venue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.dlfs.DLFSDriveManager;
import convex.dlfs.DLFSDrive;
import convex.dlfs.DLFSDrives;
import covia.adapter.DLFSAdapter;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.exception.UnavailableException;
import covia.lattice.Workspace;

/**
 * A user's view of an application transaction's draft: their workspace at one
 * venue and their drives ({@link Engine#transact}).
 */
final class UserDraft implements UserTransaction {
	private final RequestContext ctx;
	private final AccountKey venueKey;
	private final CoviaApplication.Draft draft;
	/** The venue's DLFS adapter, or null when it runs none. */
	private final DLFSAdapter dlfs;
	/** The user's drives in the draft, connected on first use. */
	private DLFSDrives drives;
	/** Drives handed out, closed when the callback returns. */
	private final List<DLFSDrive> opened = new ArrayList<>();
	private boolean finished = false;

	UserDraft(RequestContext ctx, AccountKey venueKey, CoviaApplication.Draft draft, DLFSAdapter dlfs) {
		this.ctx = ctx;
		this.venueKey = venueKey;
		this.draft = draft;
		this.dlfs = dlfs;
	}

	@Override
	public AString userDID() {
		return ctx.getUserDID();
	}

	@Override
	public Workspace workspace() {
		requireOpen();
		User user = draft.venue(venueKey).users().user(ctx.getUserDID());
		if (user == null) throw new NotFoundException(VenueState.UNKNOWN_USER, ctx.getUserDID().toString());
		return user.workspace();
	}

	@Override
	public DLFSDrive drive(String name) {
		DLFSDrive drive = drives(name).getOrCreateDrive(name);
		if (drive == null) throw new UnavailableException(DLFSAdapter.DRIVE_UNAVAILABLE + name);
		opened.add(drive);
		return drive;
	}

	@Override
	public DLFSDrive existingDrive(String name) {
		DLFSDrive drive = drives(name).drive(name);
		if (drive != null) opened.add(drive);
		return drive;
	}

	/** The user's drives in the draft, once {@code name} is known to be a drive's name. */
	private DLFSDrives drives(String name) {
		requireOpen();
		if (!DLFSDriveManager.isValidDriveName(name)) throw new InvalidInputException(DLFSAdapter.INVALID_DRIVE_NAME);
		if (dlfs == null) throw new UnavailableException(Engine.NO_DLFS);
		if (drives == null) drives = dlfs.drives(ctx, draft);
		return drives;
	}

	/** Ends the transaction's use of this view: its drives close and nothing more can be reached. */
	void finish() {
		finished = true;
		for (DLFSDrive drive : opened) {
			try {
				drive.close();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}

	private void requireOpen() {
		if (finished) throw new IllegalStateException(CoviaApplication.DRAFT_FINISHED);
	}
}
