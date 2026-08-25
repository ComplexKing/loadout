package dev.loadout.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Where Loadout keeps everything, and how profiles and snapshots are read and written.
 *
 * <pre>
 *   ~/.loadout/
 *     store/        every mod file, addressed by hash and shared by all profiles
 *     profiles/     one folder per instance, each with profile.json and a mods dir
 *     snapshots/    point-in-time copies of a profile, for rollback
 * </pre>
 *
 * <p>Plain files, no database and no server. That means an install stays inspectable,
 * survives Loadout being uninstalled, and can be backed up by copying a folder.
 */
public final class LoadoutHome {
	private static final DateTimeFormatter SNAPSHOT_STAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneId.systemDefault());

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path root;
	private final ModStore store;

	public LoadoutHome(Path root) {
		this.root = root;
		this.store = new ModStore(root.resolve("store"));
	}

	/** Default location, overridable with {@code LOADOUT_HOME} for portable installs. */
	public static LoadoutHome defaultHome() {
		String override = System.getenv("LOADOUT_HOME");
		Path root = override != null && !override.isBlank()
				? Path.of(override)
				: Path.of(System.getProperty("user.home"), ".loadout");
		return new LoadoutHome(root);
	}

	public Path root() {
		return this.root;
	}

	public ModStore store() {
		return this.store;
	}

	/**
	 * Where Minecraft itself lives: client jars, libraries and assets.
	 *
	 * <p>Shared across profiles rather than duplicated per instance. Assets alone are
	 * several hundred megabytes, and every profile on the same version wants the same ones.
	 */
	public Path minecraftRoot() {
		return this.root.resolve("minecraft");
	}

	/** Where a profile's session logs are kept. */
	public Path logFile(String profileName) {
		return profileDir(profileName).resolve("logs").resolve("latest.log");
	}

	public Path profileDir(String name) {
		return this.root.resolve("profiles").resolve(name);
	}

	public Path modsDir(String name) {
		return profileDir(name).resolve("mods");
	}

	public Path configDir(String name) {
		return profileDir(name).resolve("config");
	}

	private Path profileFile(String name) {
		return profileDir(name).resolve("profile.json");
	}

	public boolean exists(String name) {
		return Files.isRegularFile(profileFile(name));
	}

	public List<String> profileNames() throws IOException {
		Path dir = this.root.resolve("profiles");
		if (!Files.isDirectory(dir)) {
			return List.of();
		}

		try (Stream<Path> entries = Files.list(dir)) {
			return entries.filter(Files::isDirectory)
					.map(p -> p.getFileName().toString())
					.filter(this::exists)
					.sorted()
					.toList();
		}
	}

	public Profile loadProfile(String name) throws IOException {
		try (Reader reader = Files.newBufferedReader(profileFile(name), StandardCharsets.UTF_8)) {
			Profile profile = GSON.fromJson(reader, Profile.class);
			if (profile == null) {
				throw new IOException("profile.json for " + name + " is empty");
			}
			return profile;
		}
	}

	public void saveProfile(Profile profile) throws IOException {
		Path file = profileFile(profile.name());
		Files.createDirectories(file.getParent());
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(profile, writer);
		}
	}

	/**
	 * Records the profile's current state so it can be returned to.
	 *
	 * <p>Taken automatically before anything destructive. A snapshot is only a list of
	 * hashes, so it costs a few kilobytes and restoring it needs no network — which is
	 * the point: the moment you most want to undo a migration is the moment it has
	 * already broken your game.
	 *
	 * @param reason a short note about what was about to happen
	 * @return the snapshot's id
	 */
	public String snapshot(Profile profile, String reason) throws IOException {
		String id = SNAPSHOT_STAMP.format(Instant.now());
		Path dir = this.root.resolve("snapshots").resolve(profile.name());
		Files.createDirectories(dir);

		Snapshot snapshot = new Snapshot(id, Instant.now().toString(), reason, profile);
		try (Writer writer = Files.newBufferedWriter(dir.resolve(id + ".json"), StandardCharsets.UTF_8)) {
			GSON.toJson(snapshot, writer);
		}
		return id;
	}

	public List<Snapshot> snapshots(String profileName) throws IOException {
		Path dir = this.root.resolve("snapshots").resolve(profileName);
		if (!Files.isDirectory(dir)) {
			return List.of();
		}

		List<Snapshot> found = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
				try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					Snapshot snapshot = GSON.fromJson(reader, Snapshot.class);
					if (snapshot != null) {
						found.add(snapshot);
					}
				}
			}
		}

		found.sort((a, b) -> b.id().compareTo(a.id()));  // newest first
		return found;
	}

	/**
	 * Every hash any profile or snapshot still refers to.
	 *
	 * <p>Snapshots count. A hash only referenced by an old snapshot is exactly what makes
	 * rollback possible, so pruning it would quietly turn "undo" into "re-download".
	 */
	public Set<String> referencedHashes() throws IOException {
		Set<String> hashes = new HashSet<>();
		for (String name : profileNames()) {
			loadProfile(name).mods().forEach(e -> hashes.add(e.sha512().toLowerCase()));
			for (Snapshot snapshot : snapshots(name)) {
				snapshot.profile().mods().forEach(e -> hashes.add(e.sha512().toLowerCase()));
			}
		}
		return hashes;
	}
}
