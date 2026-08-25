package dev.loadout.core.browse;

import java.util.List;

/**
 * One project from a Modrinth search.
 *
 * @param projectId stable identity, used for everything downstream
 * @param slug the human-readable url segment
 * @param title display name
 * @param description the one-line summary shown in listings
 * @param author the publishing user or organisation
 * @param downloads total downloads, the closest thing to a trust signal a listing has
 * @param iconUrl may be null; plenty of projects have no icon
 * @param categories tags including the loaders it supports
 * @param latestVersion newest Minecraft version it supports, for showing staleness
 */
public record SearchResult(
		String projectId,
		String slug,
		String title,
		String description,
		String author,
		int downloads,
		int follows,
		String iconUrl,
		List<String> categories,
		String latestVersion
) {
	public String url() {
		return "https://modrinth.com/mod/" + (this.slug != null ? this.slug : this.projectId);
	}

	/** Downloads as something readable: 1.2M rather than 1234567. */
	public String downloadsShort() {
		if (this.downloads >= 1_000_000) {
			return String.format("%.1fM", this.downloads / 1_000_000.0);
		}
		if (this.downloads >= 1_000) {
			return String.format("%.0fk", this.downloads / 1_000.0);
		}
		return Integer.toString(this.downloads);
	}
}
