package dev.loadout.core.source;

import java.util.List;

/**
 * A downloadable build of a mod.
 *
 * @param source which registry this came from
 * @param modId the mod's id within that source
 * @param versionId this specific release's id within that source
 * @param versionNumber the author's own version string
 * @param fileName what the jar should be called on disk
 * @param downloadUrl where to fetch it, or null when the source will not serve it
 * @param sha512 hash for verification, when the source publishes one
 * @param fileSize bytes, 0 when unknown
 * @param requiredDependencies mod ids within the same source that this needs
 */
public record RemoteFile(
		SourceId source,
		String modId,
		String versionId,
		String versionNumber,
		String fileName,
		String downloadUrl,
		String sha512,
		long fileSize,
		List<String> requiredDependencies
) {
	/**
	 * Whether Loadout is allowed to fetch this itself.
	 *
	 * <p>CurseForge lets an author forbid third-party downloads, and when they have, the
	 * API returns the file with no url at all. That is a deliberate choice by the author
	 * rather than an error, so the only correct response is to send the person to the
	 * website rather than to find some other way to get the bytes.
	 */
	public boolean isDownloadable() {
		return this.downloadUrl != null && !this.downloadUrl.isBlank();
	}

	public RemoteFile withResolvedHash(String actualSha512) {
		return new RemoteFile(this.source, this.modId, this.versionId, this.versionNumber,
				this.fileName, this.downloadUrl, actualSha512, this.fileSize, this.requiredDependencies);
	}
}
