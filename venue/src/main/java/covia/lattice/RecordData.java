package covia.lattice;

import java.util.Arrays;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Path;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ATransformCursor;
import covia.exception.AuthException;

/**
 * Data below a component that only the component's own operations change,
 * such as an agent's {@code config/model}, a job's fields or a secret's
 * record: where a path ends below any component but a {@link Workspace}.
 *
 * <p>It reads as its parent shows that data ({@link ACoviaComponent#read(Path)}),
 * so a job's fields read in the job's current state and a user's record as
 * stored. It is read-only: a change by path is refused with
 * {@link ACoviaComponent#READ_ONLY} ({@link Entry#of}), and its cursor refuses
 * every write.</p>
 */
public final class RecordData extends ACoviaComponent<ACell> {

	private final ACoviaComponent<?> parent;
	private final Path path;

	/**
	 * @param parent The component the data is below
	 * @param path The data's path below the parent, a copy of the caller's segments
	 */
	RecordData(ACoviaComponent<?> parent, Path path) {
		super(view(parent, path));
		this.parent = parent;
		this.path = path;
	}

	/** A read-only cursor over the parent's, reading the data as the parent shows it. */
	private static <T extends ACell> AForkableCursor<ACell> view(ACoviaComponent<T> parent, Path path) {
		return new ATransformCursor<ACell, T>(parent.cursor().readOnly()) {
			@Override protected ACell view(T stored) { return parent.read(path); }
			@Override protected T write(T current, ACell value) { throw new AuthException(READ_ONLY); }
		};
	}

	/** The component the data is below. */
	public ACoviaComponent<?> parent() {
		return parent;
	}

	/** The data's path below its parent. */
	public Path path() {
		return path;
	}

	@Override
	public ACoviaComponent<?> resolve(AString[] segments, int start, int end) {
		if (start == end) return this;
		return new RecordData(parent, path.concat(Path.of(Arrays.copyOfRange(segments, start, end))));
	}

	@Override
	public ACell read() {
		return parent.read(path);
	}

	/** Whether a value is here now; a stored null counts. */
	@Override
	public boolean exists() {
		return parent.contains(path);
	}

	@Override
	public ACell read(Path below) {
		return parent.read(path.concat(below));
	}

	@Override
	public boolean contains(Path below) {
		return parent.contains(path.concat(below));
	}
}
