package covia.exception;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data the venue keeps for itself failed its integrity check, such as a
 * secret it sealed that no longer opens. That happens only through a bug,
 * damage to the store, tampering or a misconfigured venue key, so the caller
 * did not cause it and the venue's operator must look into it. The message
 * says which data failed and, where there is one, how its owner can replace
 * it, in words a person reading the log or a model can act on.
 *
 * <p>It is logged once at ERROR. A layer that only detects the failure,
 * without knowing whose data it is, throws it with the constructor. The layer
 * that reports it, knowing whose it is, raises it with {@link #found}, which
 * logs it naming the owner, passing the detected one as the cause. One that
 * escapes unlogged is logged by the scope it escapes, as a fault is
 * ({@link FaultException#within}).</p>
 */
@SuppressWarnings("serial")
public class IntegrityException extends CoviaException {

	private static final Logger log = LoggerFactory.getLogger(IntegrityException.class);

	/** Whether this failure has been logged, so that it is logged once. */
	private final AtomicBoolean logged = new AtomicBoolean();

	public IntegrityException(String message) {
		super(message);
	}

	public IntegrityException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * An integrity failure as it is reported, logged here once at ERROR with
	 * whose data failed and the failure that revealed it.
	 *
	 * @param message which data failed its check, for the caller and the log
	 * @param owner whose data it is, for the log only, such as a user's DID
	 * @param cause the failure that revealed it, or null
	 * @return the exception to throw
	 */
	public static IntegrityException found(String message, Object owner, Throwable cause) {
		IntegrityException failure = new IntegrityException(message, cause);
		failure.logged.set(true);
		log.error("{} (data of {})", message, owner, cause);
		return failure;
	}

	/**
	 * Logs this failure at ERROR as a scope reports it, unless it has been
	 * logged already.
	 *
	 * @param what what failed, as the scope's caller knows it
	 */
	void logOnce(String what) {
		if (logged.compareAndSet(false, true)) log.error("{} failed: {}", what, getMessage(), this);
	}
}
