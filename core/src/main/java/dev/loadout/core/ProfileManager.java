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
