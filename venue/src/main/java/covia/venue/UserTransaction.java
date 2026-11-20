package covia.venue;

import java.io.IOException;

import convex.core.data.AString;
import convex.dlfs.DLFSDrive;
import covia.lattice.Workspace;

/**
 * One user's changes in an application transaction ({@link Engine#transact}):
 * their workspace at the venue and their DLFS drives. Everything changed
 * through it commits together, or none of it does. Use it, and the drives and
 * workspace it gives, only inside the callback; its drives close when the
 * callback returns.
 */
public interface UserTransaction {

	/**
	 * The user this transaction acts for.
	 *
	 * @return The user's DID
	 */
	AString userDID();

	/**
	 * The user's workspace at the venue, in this transaction.
	 *
	 * @return The workspace
	 */
	Workspace workspace();

	/**
	 * The user's drive, created in this transaction if it is absent or deleted.
	 *
	 * @param name The drive's name
	 * @return The drive
	 * @throws covia.exception.InvalidInputException if the name is not a valid
	 *         drive name ({@link covia.adapter.DLFSAdapter#INVALID_DRIVE_NAME})
	 * @throws covia.exception.UnavailableException if the drive cannot be
	 *         opened or created, or the venue runs no DLFS ({@link Engine#NO_DLFS})
	 */
	DLFSDrive drive(String name);

	/**
	 * The user's drive, if it exists.
	 *
	 * @param name The drive's name
	 * @return The drive, or null if it is absent or deleted
	 * @throws covia.exception.InvalidInputException if the name is not a valid
	 *         drive name ({@link covia.adapter.DLFSAdapter#INVALID_DRIVE_NAME})
	 * @throws covia.exception.UnavailableException if the venue runs no DLFS
	 *         ({@link Engine#NO_DLFS})
	 */
	DLFSDrive existingDrive(String name);

	/**
	 * A user's changes, made through the transaction.
	 *
	 * @param <R> The result type
	 */
	@FunctionalInterface
	interface Work<R> {
		/**
		 * Makes the changes. It runs once; its exception propagates and nothing applies.
		 *
		 * @param tx The transaction
		 * @return The result
		 * @throws IOException from drive work, which applies nothing
		 */
		R apply(UserTransaction tx) throws IOException;
	}
}
