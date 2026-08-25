package dev.loadout.core.auth;

/**
 * A Microsoft account that has completed authentication at least once.
 *
 * <p>Deliberately holds no access token. Access tokens are short lived and are fetched
 * fresh when the game is launched; what persists is the refresh token, and only that.
 * Storing an access token would mean a stale credential sitting on disk for no benefit.
 *
 * @param username the Minecraft profile name
 * @param uuid the account's real Minecraft UUID
 * @param refreshToken used to obtain a new access token without signing in again. A
 *     secret: never logged, never printed, never included in a crash report.
 * @param verifiedAt when this account last completed a full Microsoft sign-in
 */
public record StoredAccount(
		String username,
		String uuid,
		String refreshToken,
		String verifiedAt
) {
	/**
	 * Whether this account can be used to launch offline.
	 *
	 * <p>Having a UUID from Minecraft's own services is the proof that matters: it can
	 * only have come from a completed sign-in against an account that owns the game.
	 */
	public boolean isVerified() {
		return this.uuid != null && !this.uuid.isBlank();
	}

	/** A copy with the secret removed, safe to show or serialise into a report. */
	public StoredAccount redacted() {
		return new StoredAccount(this.username, this.uuid, null, this.verifiedAt);
	}
}
