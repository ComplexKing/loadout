package dev.loadout.core.source;

/**
 * What kind of thing is being searched for.
 *
 * <p>The registries agree that these are different categories and disagree about how to
 * say so -- Modrinth uses a project_type facet, CurseForge a numeric class id -- so the
 * distinction lives here and each source translates it.
 *
 * <p>Only the kinds a launcher installs into an instance. Modpacks are deliberately
 * absent: installing one is not adding content to an instance, it is creating one, and
 * conflating those would make "add" mean two different things.
 */
public enum ContentType {
	MOD("mod", "mods", 6),
	RESOURCE_PACK("resourcepack", "resourcepacks", 12),
	SHADER("shader", "shaderpacks", 6552),
	DATAPACK("datapack", "datapacks", 6945);

	private final String key;
	private final String folder;
	private final int curseForgeClassId;

	ContentType(String key, String folder, int curseForgeClassId) {
		this.key = key;
		this.folder = folder;
		this.curseForgeClassId = curseForgeClassId;
	}

	/** Stable string used by the API and by Modrinth's project_type facet. */
	public String key() {
		return this.key;
	}

	/** Where the game expects files of this kind inside an instance. */
	public String folder() {
		return this.folder;
	}

	public int curseForgeClassId() {
		return this.curseForgeClassId;
	}

	/**
	 * Whether a loader facet applies.
	 *
	 * <p>Resource packs and shaders are loader-agnostic, and filtering them by "fabric"
	 * returns nothing at all -- which reads as the search being broken rather than as the
	 * filter being meaningless.
	 */
	public boolean usesLoader() {
		return this == MOD;
	}

	public static ContentType fromKey(String key) {
		for (ContentType type : values()) {
			if (type.key.equalsIgnoreCase(key)) {
				return type;
			}
		}
		return null;
	}
}
