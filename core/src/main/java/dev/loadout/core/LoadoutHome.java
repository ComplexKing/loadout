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

	public Settings settings() {
		return Settings.load(this.root);
	}

	/**
	 * The sources available on this machine.
	 *
	 * <p>Built fresh each call so a key added during a session takes effect without a
	 * restart -- the registry is cheap, and a stale one silently ignoring new credentials
	 * is a confusing thing to debug.
	 */
	public dev.loadout.core.source.SourceRegistry sources() {
		return new dev.loadout.core.source.SourceRegistry(settings().curseForgeApiKey());
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
		return this.root.resolve("profiles").resolve(requireValidName(name));
	}

	/**
	 * Rejects anything that is not a plain folder name.
	 *
	 * <p>Every path in a profile is built by resolving its name against a directory, so a
	 * name containing a separator or ".." would escape the profiles folder
	 * entirely -- and "delete this profile" would become "delete that folder". Harmless
	 * while names only came from a person typing a CLI argument; not once they arrive over
	 * HTTP from a UI.
	 *
	 * <p>Validating here rather than at each caller is deliberate: this is the one place
	 * every profile path is constructed, so nothing can route around it.
	 */
	public static String requireValidName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Profile name cannot be empty");
		}
		if (name.length() > 64) {
			throw new IllegalArgumentException("Profile name is too long (max 64 characters)");
		}
		if (name.contains("/") || name.contains("\\") || name.chars().anyMatch(c -> c < 0x20)) {
			throw new IllegalArgumentException(
					"Profile name cannot contain path separators or control characters: " + name);
		}
		if (name.equals(".") || name.equals("..")) {
			throw new IllegalArgumentException("Profile name cannot be " + name);
		}
		// Windows resolves these to devices wherever they appear as a file name, with or
		// without an extension, so a profile called "CON" would produce paths that open a
		// console handle instead of a folder.
		String stem = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
		if (RESERVED_NAMES.contains(stem.toUpperCase())) {
			throw new IllegalArgumentException("Profile name is reserved by Windows: " + name);
		}
		// Trailing dots and spaces are silently stripped by Windows, so "foo " and "foo"
		// would be the same folder while looking like different profiles.
		if (!name.equals(name.strip()) || name.endsWith(".")) {
			throw new IllegalArgumentException("Profile name cannot start or end with spaces or a dot");
		}
		return name;
	}

	private static final Set<String> RESERVED_NAMES = Set.of(
			"CON", "PRN", "AUX", "NUL",
			"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
			"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

	/**
	 * Removes a profile, leaving its snapshots behind.
	 *
	 * <p>Keeping the snapshots is what makes this recoverable: they hold the full mod list
	 * and the store still has the files, so a deletion can be undone until someone runs a
	 * prune. That matters more with a UI than it did with a CLI -- a button is much easier
	 * to press by accident than a command is to type.
	 *
	 * @return false if there was no such profile
	 */
	public boolean deleteProfile(String name) throws IOException {
		if (!exists(name)) {
			return false;
		}

		Path dir = profileDir(name);
		try (Stream<Path> walk = Files.walk(dir)) {
			// Deepest first, because a directory cannot be removed until it is empty.
			for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
		return true;
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
