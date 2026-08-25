package dev.loadout.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An instance: a Minecraft version, a loader, and the set of mods that go with it.
 *
 * <p>Mods are recorded as hashes rather than file paths. That makes a profile a
 * description of a state rather than a folder of files, which is what lets it be
 * snapshotted, diffed, rolled back and moved to another Minecraft version as data.
 */
public final class Profile {
	private String name;
	private String minecraftVersion;
	private String loader;
	private List<Entry> mods;

	/**
	 * One mod in a profile.
	 *
	 * @param sha512 identity, and the key into {@link ModStore}
	 * @param fileName what it should be called on disk
	 * @param enabled whether it's active
	 * @param projectId Modrinth project, when known. Kept so updates can be found later
	 *     without re-resolving, and so a mod stays identifiable after its hash changes.
	 * @param versionNumber the author's version string, for display
	 * @param modId the id from fabric.mod.json. This is what actually decides whether two
	 *     jars conflict: the loader refuses to start with two mods declaring the same id,
	 *     and unlike a registry id it is present in every jar whether or not anyone has
	 *     ever published it.
	 * @param source which registry {@code projectId} belongs to. Recorded because the two
	 *     id spaces are unrelated -- looking a CurseForge id up on Modrinth finds nothing,
	 *     and a mod installed from CurseForge has to keep being checked against CurseForge.
	 */
	public record Entry(
			String sha512,
			String fileName,
			boolean enabled,
			String projectId,
			String versionNumber,
			String modId,
			String source
	) {
		public Entry withEnabled(boolean value) {
			return new Entry(this.sha512, this.fileName, value, this.projectId,
					this.versionNumber, this.modId, this.source);
		}

		public dev.loadout.core.source.SourceId sourceId() {
			return this.source == null ? null : dev.loadout.core.source.SourceId.fromKey(this.source);
		}
	}

	// Gson needs this; everything else should use the other constructor.
	private Profile() {
	}

	public Profile(String name, String minecraftVersion, String loader, List<Entry> mods) {
		this.name = name;
		this.minecraftVersion = minecraftVersion;
		this.loader = loader;
		this.mods = new ArrayList<>(mods);
	}

	public String name() {
		return this.name;
	}

	public String minecraftVersion() {
		return this.minecraftVersion;
	}

	public String loader() {
		return this.loader;
	}

	/** Unmodifiable; use the mutators so changes stay explicit. */
	public List<Entry> mods() {
		return List.copyOf(this.mods == null ? List.of() : this.mods);
	}

	public List<Entry> enabledMods() {
		return mods().stream().filter(Entry::enabled).toList();
	}

	public Optional<Entry> byFileName(String fileName) {
		return mods().stream().filter(e -> e.fileName().equalsIgnoreCase(fileName)).findFirst();
	}

	public void setMods(List<Entry> replacement) {
		this.mods = new ArrayList<>(replacement);
	}

	public void setMinecraftVersion(String version) {
		this.minecraftVersion = version;
	}

	public void setName(String value) {
		this.name = value;
	}

	/** Builds a profile from whatever is already sitting in a mods folder. */
	public static Profile fromScan(String name, String minecraftVersion, String loader, List<ModJar> jars) {
		List<Entry> entries = new ArrayList<>(jars.size());
		for (ModJar jar : jars) {
			entries.add(new Entry(jar.sha512(), jar.fileName(), jar.enabled(), null,
					jar.version(), jar.modId(), null));
		}
		return new Profile(name, minecraftVersion, loader, entries);
	}
}
