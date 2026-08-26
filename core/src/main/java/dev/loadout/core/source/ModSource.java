package dev.loadout.core.source;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * A place mods can be searched for and fetched from.
 *
 * <p>Deliberately small. Everything Loadout does with a registry reduces to four
 * questions — what is there, which build fits, what does it need, and where do I get it —
 * and keeping the interface at that size is what lets a new source be added without
 * touching the installer, the profile model or the interface.
 *
 * <p>Sources differ in ways this interface hides but callers still feel. Modrinth is open
 * and identifies files by SHA-512; CurseForge needs an API key, uses its own fingerprint
 * scheme, and lets authors forbid third-party downloads entirely. So
 * {@link #isAvailable()} exists, and {@link RemoteFile#isDownloadable()} exists, and both
 * have to be checked rather than assumed.
 */
public interface ModSource {
	SourceId id();

	/**
	 * Whether this source can be used right now.
	 *
	 * <p>False when a required credential is missing. A source that cannot work should
	 * say so up front and be left out of a search, rather than failing per-request and
	 * making every query look broken.
	 */
	boolean isAvailable();

	/** Why the source is unavailable, for showing the user. Null when it is available. */
	String unavailableReason();

	/**
	 * Actually checks the source works, credentials included.
	 *
	 * <p>Distinct from {@link #isAvailable()}, which only reports whether a key is
	 * present. A key can be present and wrong -- mistyped, truncated by a copy, or
	 * revoked -- and the difference only shows up when a request is made. Without this,
	 * the first sign of a bad key is an empty half of a search result, which reads as
	 * the feature being broken rather than the credential.
	 *
	 * <p>Makes a network call, so it belongs at the point a key is entered rather than
	 * in a listing.
	 */
	default Verification verify() {
		return isAvailable()
				? Verification.ok()
				: Verification.failed(unavailableReason());
	}

	/**
	 * @param succeeded named this rather than "ok" so the factory below can be called
	 *     {@code ok()} -- a record component of that name would claim the accessor
	 * @param detail what went wrong, or null when nothing did
	 */
	record Verification(boolean succeeded, String detail) {
		public static Verification ok() {
			return new Verification(true, null);
		}

		public static Verification failed(String detail) {
			return new Verification(false, detail);
		}
	}

	/**
	 * Searches for mods matching a query.
	 *
	 * @param query free text; empty means "most popular for these filters"
	 * @param gameVersion Minecraft version to filter to
	 * @param loader loader to filter to, e.g. "fabric"
	 * @param sort a {@link SortOrder}
	 */
	List<RemoteMod> search(String query, String gameVersion, String loader,
			SortOrder sort, int limit, int offset) throws IOException, InterruptedException;

	/** The newest build of a mod for a given target, if there is one. */
	Optional<RemoteFile> bestFile(String modId, String gameVersion, String loader)
			throws IOException, InterruptedException;

	/**
	 * Artwork for several mods at once, keyed by the id asked for.
	 *
	 * <p>Bulk because the caller is an installed-mods list: asking per mod would be one
	 * request per row, which is both slow and a good way to be rate limited by a registry
	 * that was being perfectly reasonable about it.
	 *
	 * <p>Missing entries are simply absent rather than an error. A mod with no artwork, or
	 * one the registry has since removed, should still render -- just without a picture.
	 */
	default java.util.Map<String, String> icons(List<String> modIds)
			throws IOException, InterruptedException {
		return java.util.Map.of();
	}

	/** A mod's display name, for reporting what a dependency actually is. */
	String modTitle(String modId) throws IOException, InterruptedException;

	/** Sort orders every source is expected to approximate. */
	enum SortOrder {
		RELEVANCE,
		DOWNLOADS,
		UPDATED,
		NEWEST
	}
}
