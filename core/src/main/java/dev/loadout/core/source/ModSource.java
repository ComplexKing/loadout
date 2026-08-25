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
