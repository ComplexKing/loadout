package dev.loadout.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Creating, changing and undoing changes to profiles. */
public final class ProfileManager {
	private final LoadoutHome home;
	private final Downloader downloader;

	public ProfileManager(LoadoutHome home) {
		this.home = home;
		this.downloader = new Downloader(home.store());
	}

	/**
	 * Adopts an existing mods folder as a profile.
	 *
	 * <p>Copies rather than moves. Someone pointing this at their live {@code .minecraft}
	 * should end up with a profile *and* a game that still works — a tool that eats the
	 * folder it was asked to read is not one people trust a second time.
	 */
	public Profile importFrom(Path modsDir, String name, String minecraftVersion, String loader)
			throws IOException {
		List<ModJar> jars = ModScanner.scan(modsDir);
		if (jars.isEmpty()) {
			throw new IOException("No mods found in " + modsDir);
		}

		// One bulk lookup resolves which Modrinth project each jar belongs to. Without
		// this, imported mods carry no project id, and a later install can't tell that the
		// profile already has Fabric API -- so it adds a second copy and the game stops
		// starting. Files Modrinth doesn't host simply stay unresolved, which is fine
		// because conflict detection falls back to the Fabric mod id.
		Map<String, ModrinthVersion> identified = Map.of();
		try {
			Set<String> hashes = new HashSet<>();
			jars.forEach(jar -> hashes.add(jar.sha512()));
			identified = new ModrinthClient().identify(hashes);
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			// Offline import is still worth doing; only the project ids are lost.
		}

		List<Profile.Entry> entries = new ArrayList<>(jars.size());
		for (ModJar jar : jars) {
			String hash = this.home.store().put(jar.path(), jar.sha512());
			ModrinthVersion known = identified.get(jar.sha512());
			entries.add(new Profile.Entry(hash, jar.fileName(), jar.enabled(),
					known == null ? null : known.projectId(), jar.version(), jar.modId(),
					known == null ? null : dev.loadout.core.source.SourceId.MODRINTH.key()));
		}

		Profile profile = new Profile(name, minecraftVersion, loader, entries);
		this.home.saveProfile(profile);
		materialise(profile);
		return profile;
	}

	/**
	 * Rewrites a profile's mods folder to match its recorded state.
	 *
	 * <p>The folder is treated as disposable output, not as the source of truth — jars in
	 * it that the profile doesn't list are removed, because a stale file Fabric still
	 * loads is exactly the sort of thing that makes a migration look broken when the
	 * profile itself is fine.
	 *
	 * @return how many files were written
	 */
	public int materialise(Profile profile) throws IOException {
		Path modsDir = this.home.modsDir(profile.name());
		Files.createDirectories(modsDir);

		if (Files.isDirectory(modsDir)) {
			try (Stream<Path> existing = Files.list(modsDir)) {
				for (Path file : existing.filter(Files::isRegularFile).toList()) {
					String fileName = file.getFileName().toString();
					if (fileName.endsWith(".jar") || fileName.endsWith(".jar" + ModScanner.DISABLED_SUFFIX)) {
						Files.delete(file);
					}
				}
			}
		}

		int written = 0;
		for (Profile.Entry entry : profile.mods()) {
			this.home.store().linkInto(entry.sha512(), modsDir, entry.fileName(), entry.enabled());
			written++;
		}
		return written;
	}

	/**
	 * Carries out a migration.
	 *
	 * <p>A snapshot is taken first, always. Everything is downloaded and verified into
	 * the store *before* the profile is rewritten, so a network failure halfway through
	 * leaves the profile untouched rather than half-migrated.
	 *
	 * @param includeLikely whether to accept entries matched by name rather than hash
	 * @return what happened
	 */
	public MigrationResult apply(Profile profile, MigrationPlan plan, boolean includeLikely,
			Downloader.Progress progress) throws IOException, InterruptedException {
		String snapshotId = this.home.snapshot(profile, "migrate to " + plan.targetGameVersion());

		List<MigrationPlan.Entry> toChange = new ArrayList<>(plan.changes());
		if (includeLikely) {
			toChange.addAll(plan.needsConfirming());
		}

		// Fetch everything first. Downloading into the content store touches nothing the
		// profile can see, so this stage is entirely safe to fail.
		Map<String, ModrinthVersion> fetched = new LinkedHashMap<>();
		for (MigrationPlan.Entry entry : toChange) {
			String hash = this.downloader.fetch(entry.target(), progress);
			fetched.put(entry.jar().sha512(), entry.target().withResolvedHash(hash));
		}

		List<Profile.Entry> updated = new ArrayList<>(profile.mods().size());
		int changed = 0;
		for (Profile.Entry entry : profile.mods()) {
			ModrinthVersion replacement = fetched.get(entry.sha512());
			if (replacement == null) {
				updated.add(entry);
				continue;
			}

			// Migration resolves through Modrinth's bulk hash endpoints, so anything it
			// replaces is a Modrinth file now, whatever the entry was before.
			updated.add(new Profile.Entry(
					replacement.sha512(),
					replacement.fileName(),
					entry.enabled(),
					replacement.projectId(),
					replacement.versionNumber(),
					entry.modId(),
					dev.loadout.core.source.SourceId.MODRINTH.key()));
			changed++;
		}

		profile.setMods(updated);
		profile.setMinecraftVersion(plan.targetGameVersion());
		this.home.saveProfile(profile);
		materialise(profile);

		return new MigrationResult(snapshotId, changed, plan.blockers().size());
	}

	/** @param snapshotId which snapshot was taken before the change, so it can be undone */
	public record MigrationResult(String snapshotId, int modsChanged, int blockersRemaining) {
	}

	/**
	 * Returns a profile to a previous state.
	 *
	 * <p>Works offline and instantly, because every file involved is still in the store —
	 * that's the whole reason rollback is worth having rather than a re-download in
	 * disguise.
	 */
	public Profile rollback(String profileName, String snapshotId) throws IOException {
		Snapshot target = this.home.snapshots(profileName).stream()
				.filter(s -> s.id().equals(snapshotId))
				.findFirst()
				.orElseThrow(() -> new IOException("No snapshot " + snapshotId + " for " + profileName));

		Profile current = this.home.loadProfile(profileName);
		// Snapshot the state we're leaving, so rolling back is itself undoable.
		this.home.snapshot(current, "before rollback to " + snapshotId);

		Profile restored = target.profile();
		restored.setName(profileName);
		this.home.saveProfile(restored);
		materialise(restored);
		return restored;
	}

	/**
	 * Copies settings from one profile to another.
	 *
	 * <p>Mod configs survive Minecraft versions far better than mods do, so a migrated
	 * profile is usually one config copy away from feeling like the old one rather than
	 * a fresh install. Existing files are overwritten only when {@code overwrite} is set,
	 * so this can be used to fill gaps without clobbering deliberate differences.
	 *
	 * @return how many files were copied
	 */
	/**
	 * Copies a profile under a new name.
	 *
	 * <p>Costs almost nothing on disk. Mods live in the content store and are hard linked
	 * into each instance, so a copy of a four hundred megabyte modpack adds four hundred
	 * links -- which is the point of the store, and what makes "try a change without
	 * risking the instance I play" a reasonable thing to do.
	 *
	 * <p>Configs are copied properly, because those are the part someone would edit and
	 * would not want shared between the original and the copy.
	 */
	public Profile duplicate(String fromName, String toName) throws IOException {
		LoadoutHome.requireValidName(toName);

		if (this.home.exists(toName)) {
			throw new IOException("A profile called '" + toName + "' already exists");
		}

		Profile source = this.home.loadProfile(fromName);
		Profile copy = new Profile(toName, source.minecraftVersion(), source.loader(), source.mods());

		this.home.saveProfile(copy);
		materialise(copy);
		syncSettings(fromName, toName, true);

		// Worlds and server lists are deliberately not copied. A duplicate is usually made
		// to test a change to the mods, and silently cloning tens of gigabytes of worlds
		// would be a surprise both on disk and in how long it took.
		return copy;
	}

	/**
	 * Writes the instance to a zip.
	 *
	 * <p>Includes profile.json, so importing it back knows the Minecraft version, loader
	 * and exact mod versions. Mod jars are included too rather than only their hashes:
	 * an export that needs the original machine's content store to be useful is not an
	 * export.
	 *
	 * @param include extra folders to carry along, e.g. "config", "resourcepacks"
	 * @return how many files were written
	 */
	public int export(String profileName, Path zipFile, Set<String> include) throws IOException {
		Path instance = this.home.profileDir(profileName);
		if (!Files.isDirectory(instance)) {
			throw new IOException("No profile called '" + profileName + "'");
		}

		Files.createDirectories(zipFile.toAbsolutePath().getParent());
		int written = 0;

		try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
				Files.newOutputStream(zipFile))) {

			written += addToZip(zip, instance.resolve("profile.json"), "profile.json");

			for (String folder : include) {
				Path dir = instance.resolve(folder);
				if (!Files.isDirectory(dir)) {
					continue;
				}
				try (Stream<Path> files = Files.walk(dir)) {
					for (Path file : files.filter(Files::isRegularFile).toList()) {
						// Built from path elements rather than by replacing separators: a zip
						// entry name is not an OS path, and hardcoding either separator makes
						// this wrong on one platform or the other.
						StringBuilder name = new StringBuilder(folder);
						for (Path part : dir.relativize(file)) {
							name.append('/').append(part);
						}
						written += addToZip(zip, file, name.toString());
					}
				}
			}
		}

		return written;
	}

	private static int addToZip(java.util.zip.ZipOutputStream zip, Path file, String name)
			throws IOException {
		if (!Files.isRegularFile(file)) {
			return 0;
		}
		zip.putNextEntry(new java.util.zip.ZipEntry(name));
		Files.copy(file, zip);
		zip.closeEntry();
		return 1;
	}

	public int syncSettings(String fromProfile, String toProfile, boolean overwrite) throws IOException {
		Path source = this.home.configDir(fromProfile);
		Path destination = this.home.configDir(toProfile);
		if (!Files.isDirectory(source)) {
			return 0;
		}

		Files.createDirectories(destination);
		int copied = 0;

		try (Stream<Path> files = Files.walk(source)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				Path relative = source.relativize(file);
				Path target = destination.resolve(relative.toString());

				if (!overwrite && Files.exists(target)) {
					continue;
				}

				Files.createDirectories(target.getParent());
				Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
				copied++;
			}
		}

		return copied;
	}
}
