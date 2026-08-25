package dev.loadout.core.source;

/** The registries Loadout knows how to talk to. */
public enum SourceId {
	MODRINTH("modrinth", "Modrinth"),
	CURSEFORGE("curseforge", "CurseForge");

	private final String key;
	private final String displayName;

	SourceId(String key, String displayName) {
		this.key = key;
		this.displayName = displayName;
	}

	/** Stable string written into profile.json; never change these. */
	public String key() {
		return this.key;
	}

	public String displayName() {
		return this.displayName;
	}

	public static SourceId fromKey(String key) {
		for (SourceId id : values()) {
			if (id.key.equalsIgnoreCase(key)) {
				return id;
			}
		}
		// An unknown source in an old or newer profile shouldn't be fatal; treat it as
		// unresolved rather than refusing to load the profile at all.
		return null;
	}
}
