package dev.loadout.core;

import java.nio.file.Path;
import java.util.List;

/**
 * One mod file on disk, as far as we can tell from the file itself.
 *
 * @param path where it lives
 * @param sha512 hash of the file, lowercase hex. This is the identity that matters:
 *     filenames get renamed, mangled by browsers, or collide between versions, but the
 *     hash resolves a jar on Modrinth no matter what it's called.
 * @param sizeBytes file size
 * @param enabled false when the file is suffixed {@code .disabled}, the convention other
 *     launchers use so a mod can be turned off without being thrown away
 * @param modId the {@code id} from fabric.mod.json, or null if it isn't a Fabric mod
 * @param name human-readable name, falling back to the file name
 * @param version the mod's own version string
 * @param minecraftVersions Minecraft versions the mod declares it depends on
 */
public record ModJar(
		Path path,
		String sha512,
		long sizeBytes,
		boolean enabled,
		String modId,
		String name,
		String version,
		List<String> minecraftVersions
) {
	/** The filename with any {@code .disabled} suffix removed. */
	public String fileName() {
		String raw = this.path.getFileName().toString();
		return raw.endsWith(ModScanner.DISABLED_SUFFIX)
				? raw.substring(0, raw.length() - ModScanner.DISABLED_SUFFIX.length())
				: raw;
	}

	/**
	 * Whether we could read Fabric metadata out of it. A jar in the mods folder that
	 * isn't a Fabric mod is usually a library the user dropped in by hand, or a
	 * NeoForge mod in the wrong place -- both worth flagging rather than hiding.
	 */
	public boolean isFabricMod() {
		return this.modId != null;
	}

	public String displayName() {
		return this.name != null ? this.name : fileName();
	}
}
