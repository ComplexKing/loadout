package dev.loadout.core.source;

import java.util.List;

/**
 * A mod as some registry describes it, independent of which registry that is.
 *
 * <p>Ids are only meaningful together with their source: Modrinth's {@code AANobbMI} and
 * CurseForge's {@code 394468} both mean Sodium, and neither is resolvable by the other.
 * Carrying the source alongside the id is what stops a lookup being sent to the wrong
 * place -- and it is also what a profile needs to record, so that a mod installed from
 * CurseForge is still checked against CurseForge a year later.
 *
 * @param source which registry this came from
 * @param id that registry's own identifier
 * @param slug url-friendly name, where the source has one
 * @param title display name
 * @param description one-line summary
 * @param author publishing user or organisation
 * @param downloads total downloads, the nearest thing to a trust signal a listing has
 * @param iconUrl may be null; plenty of projects have no icon
 * @param categories tags, including supported loaders
 */
public record RemoteMod(
		SourceId source,
		String id,
		String slug,
		String title,
		String description,
		String author,
		long downloads,
		String iconUrl,
		List<String> categories
) {
	/** Downloads as something readable: 1.2M rather than 1234567. */
	public String downloadsShort() {
		if (this.downloads >= 1_000_000) {
			return String.format("%.1fM", this.downloads / 1_000_000.0);
		}
		if (this.downloads >= 1_000) {
			return String.format("%.0fk", this.downloads / 1_000.0);
		}
		return Long.toString(this.downloads);
	}

	/** Where a person would go to read about this mod. */
	public String webUrl() {
		return switch (this.source) {
			case MODRINTH -> "https://modrinth.com/mod/" + (this.slug != null ? this.slug : this.id);
			case CURSEFORGE -> this.slug != null
					? "https://www.curseforge.com/minecraft/mc-mods/" + this.slug
					: "https://www.curseforge.com/projects/" + this.id;
		};
	}
}
