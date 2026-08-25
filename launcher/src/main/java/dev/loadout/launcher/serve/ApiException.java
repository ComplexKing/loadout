package dev.loadout.launcher.serve;

/**
 * A failure with an HTTP status already decided.
 *
 * <p>Unchecked on purpose. Validation happens several frames deep inside handlers, and
 * threading a status back up through checked exceptions turns every helper signature
 * into plumbing. Anything that escapes without being one of these is a bug rather than a
 * bad request, and the dispatcher reports it as a 500.
 */
final class ApiException extends RuntimeException {
	private final int status;

	ApiException(int status, String message) {
		super(message);
		this.status = status;
	}

	int status() {
		return this.status;
	}

	static ApiException notFound(String what) {
		return new ApiException(404, what);
	}
}
