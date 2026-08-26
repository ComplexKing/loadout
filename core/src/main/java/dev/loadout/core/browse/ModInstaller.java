package dev.loadout.core.browse;

import dev.loadout.core.Downloader;
import dev.loadout.core.LoadoutHome;
import dev.loadout.core.ModScanner;
import dev.loadout.core.Profile;
import dev.loadout.core.ProfileManager;
import dev.loadout.core.instance.Unzip;
import dev.loadout.core.source.ContentType;
import dev.loadout.core.source.RemoteFile;
import dev.loadout.core.source.SourceId;
import dev.loadout.core.source.SourceRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Adds and removes mods in a profile, pulling in whatever they depend on. */
public final class ModInstaller {
	private final LoadoutHome home;
	private final ProfileManager profiles;
	private final Downloader downloader;

	public ModInstaller(LoadoutHome home) {
		this.home = home;
		this.profiles = new ProfileManager(home);
		this.downloader = new Downloader(home.store());
	}

	/**
	 * @param installed files added
	 * @param upgraded files replaced in place, as "old -> new"
	 * @param alreadyPresent mods the profile already had
	 * @param unavailable mods with no build for this profile's Minecraft version
	 * @param blocked mods whose author has forbidden third-party downloads, with the page
	 *     to get them from. Not an error — a decision by the author that Loadout respects.
	 */
	public record Result(
			List<String> installed,
			List<String> upgraded,
			List<String> alreadyPresent,
			List<String> unavailable,
			List<String> blocked
	) {
		public boolean changedAnything() {
			return !this.installed.isEmpty() || !this.upgraded.isEmpty();
		}

		static Result nothing(List<String> present, List<String> unavailable, List<String> blocked) {
			return new Result(List.of(), List.of(), present, unavailable, blocked);
		}
	}

	/**
	 * Installs a mod and everything it requires.
	 *
	 * <p>Dependencies resolve breadth-first and transitively within the same source, since
	 * a library often depends on another. Installing only what was asked for produces a
	 * profile that fails at startup with a missing-dependency error, which is the most
	 * common way a hand-managed mods folder breaks.
	 *
	 * <p>Dependencies stay within the source they came from. CurseForge ids mean nothing
	 * to Modrinth and vice versa, so following a dependency across registries would need
	 * name matching — and quietly installing a different project than the one a mod asked
	 * for is worse than reporting that it couldn't be resolved.
	 */
	public Result install(String profileName, SourceId sourceId, String modId, Downloader.Progress progress)
			throws IOException, InterruptedException {
		return install(profileName, sourceId, modId, null, ContentType.MOD, progress);
	}

	public Result install(String profileName, SourceId sourceId, String modId, String versionId,
			Downloader.Progress progress) throws IOException, InterruptedException {
		return install(profileName, sourceId, modId, versionId, ContentType.MOD, progress);
	}

	/**
	 * Installs a particular build rather than the newest one.
	 *
	 * <p>Dependencies still resolve to their newest matching build. Pinning a mod is a
	 * statement about that mod -- a beta to avoid, a regression to step back from -- and
	 * says nothing about what its libraries should be.
	 *
	 * @param versionId the source's own version id, or null for whatever fits best
	 */
	public Result install(String profileName, SourceId sourceId, String modId, String versionId,
			ContentType type, Downloader.Progress progress) throws IOException, InterruptedException {
		Profile profile = this.home.loadProfile(profileName);
		SourceRegistry registry = this.home.sources();

		var source = registry.get(sourceId);
		if (source == null || !source.isAvailable()) {
			throw new IOException(sourceId.displayName() + " is not available"
					+ (source == null ? "" : ": " + source.unavailableReason()));
		}

		Set<String> present = new HashSet<>();
		for (Profile.Entry entry : profile.mods()) {
			if (entry.projectId() != null && sourceId.key().equals(entry.source())) {
				present.add(entry.projectId());
			}
		}

		List<String> skipped = new ArrayList<>();
		List<String> unavailable = new ArrayList<>();
		List<String> blocked = new ArrayList<>();
		Map<String, RemoteFile> toAdd = new LinkedHashMap<>();

		Deque<String> queue = new ArrayDeque<>();
		Set<String> seen = new HashSet<>();
		queue.add(modId);
		seen.add(modId);

		while (!queue.isEmpty()) {
			String current = queue.poll();

			// Asking for a specific build of the mod you named is a request to change to
			// that build, so "you already have this mod" is not an answer -- having it at a
			// different version is the whole reason for the request. Dependencies are still
			// skipped when present, because nothing was said about them.
			boolean pinned = versionId != null && current.equals(modId);

			if (!pinned && present.contains(current)) {
				skipped.add(source.modTitle(current));
				continue;
			}

			// The pinned version applies to the mod that was asked for, never to something
			// pulled in behind it.
			// The loader is only a meaningful filter for mods. Asking CurseForge for the
			// Fabric build of a world matches nothing at all, which showed up as every
			// world being unavailable rather than as a filter that should not have applied.
			String loader = type.usesLoader() ? profile.loader() : null;

			Optional<RemoteFile> file = versionId != null && current.equals(modId)
					? source.fileForVersion(current, versionId)
					: source.bestFile(current, profile.minecraftVersion(), loader);
			if (file.isEmpty()) {
				unavailable.add(source.modTitle(current));
				continue;
			}

			// Check again now the canonical id is known. A mod can be asked for by slug --
			// "sodium" from a command line, or from a listing -- while a dependency always
			// names the registry's own id. Comparing only what was requested means an
			// already-installed dependency looks absent, gets resolved again, and is
			// reported as an upgrade of a mod to itself.
			String canonical = file.get().modId();
			if (!pinned && canonical != null && !canonical.equals(current)) {
				if (present.contains(canonical) || toAdd.containsKey(canonical)) {
					skipped.add(source.modTitle(current));
					continue;
				}
			}

			if (!file.get().isDownloadable()) {
				// CurseForge authors can opt out of third-party distribution. Point at
				// the page rather than pretending this is a failure.
				blocked.add(source.modTitle(current));
				continue;
			}

			// Keyed by the canonical id so two routes to the same mod -- once by slug, once
			// as another mod's dependency -- collapse into one entry.
			toAdd.put(canonical != null ? canonical : current, file.get());
			for (String dependency : file.get().requiredDependencies()) {
				if (seen.add(dependency)) {
					queue.add(dependency);
				}
			}
		}

		if (toAdd.isEmpty()) {
			return Result.nothing(List.copyOf(skipped), List.copyOf(unavailable), List.copyOf(blocked));
		}

		// A world is unpacked rather than placed. After that it is an ordinary world the
		// game owns and edits, so it is not recorded as a profile entry -- tracking a
		// version of something that diverges the first time it is played would be a
		// promise this cannot keep.
		if (type.isArchive()) {
			return unpack(profile, toAdd, source, progress, skipped, unavailable, blocked);
		}

		// Everything downloads into the content store first. That touches nothing the
		// profile can see, so this stage is safe to fail.
		Map<String, String> hashes = new LinkedHashMap<>();
		for (Map.Entry<String, RemoteFile> entry : toAdd.entrySet()) {
			hashes.put(entry.getKey(), this.downloader.fetch(entry.getValue(), progress));
		}

		this.home.snapshot(profile, "install " + source.modTitle(modId));

		List<Profile.Entry> updated = new ArrayList<>(profile.mods());
		List<String> installed = new ArrayList<>();
		List<String> upgraded = new ArrayList<>();

		for (Map.Entry<String, RemoteFile> entry : toAdd.entrySet()) {
			RemoteFile file = entry.getValue();
			String hash = hashes.get(entry.getKey());

			// The id inside the jar is what decides a conflict. Fabric refuses to start
			// with two mods declaring the same id, and a registry id cannot catch it --
			// the same mod from another source has a different one entirely. Only mods
			// carry such an id; a resource pack zip has nothing to read.
			String jarModId = type == ContentType.MOD ? readModId(hash) : null;

			Profile.Entry replacement = new Profile.Entry(hash, file.fileName(), true,
					entry.getKey(), file.versionNumber(), jarModId, sourceId.key(), type.key());

			int existing = indexOfModId(updated, jarModId);
			if (existing >= 0) {
				upgraded.add(updated.get(existing).fileName() + " -> " + file.fileName());
				updated.set(existing, replacement.withEnabled(updated.get(existing).enabled()));
			} else {
				updated.add(replacement);
				installed.add(file.fileName());
			}
		}

		profile.setMods(updated);
		this.home.saveProfile(profile);
		this.profiles.materialise(profile);

		return new Result(List.copyOf(installed), List.copyOf(upgraded),
				List.copyOf(skipped), List.copyOf(unavailable), List.copyOf(blocked));
	}

	/**
	 * Downloads and unpacks archives into the instance.
	 *
	 * <p>Used for worlds. The archive goes through the content store like anything else,
	 * so a repeated install is free, but what lands in the instance is the unpacked
	 * contents rather than a link to the file.
	 */
	private Result unpack(Profile profile, Map<String, RemoteFile> toAdd, dev.loadout.core.source.ModSource source,
			Downloader.Progress progress, List<String> skipped, List<String> unavailable,
			List<String> blocked) throws IOException, InterruptedException {

		Path saves = this.home.profileDir(profile.name())
				.resolve(ContentType.WORLD.folder());
		Files.createDirectories(saves);

		List<String> installed = new ArrayList<>();

		for (Map.Entry<String, RemoteFile> entry : toAdd.entrySet()) {
			RemoteFile file = entry.getValue();
			String hash = this.downloader.fetch(file, progress);

			// Unpacked somewhere temporary first: an archive that turns out not to contain a
			// world, or that fails halfway, should not leave a broken folder in saves/.
			Path staging = Files.createTempDirectory("loadout-world-");
			try {
				Unzip.into(this.home.store().pathFor(hash), staging);

				Path root = Unzip.findWorldRoot(staging);
				if (root == null) {
					unavailable.add(source.modTitle(entry.getKey()) + " (no world found in the download)");
					continue;
				}

				Path destination = uniqueDirectory(saves, root.getFileName().toString());
				Files.move(root, destination);
				installed.add(destination.getFileName().toString());
			} finally {
				deleteTree(staging);
			}
		}

		return new Result(List.copyOf(installed), List.of(), List.copyOf(skipped),
				List.copyOf(unavailable), List.copyOf(blocked));
	}

	/**
	 * A folder name not already taken.
	 *
	 * <p>Installing the same world twice should give two worlds rather than overwriting the
	 * one already there, which by then has hours of play in it.
	 */
	private static Path uniqueDirectory(Path parent, String preferred) {
		Path candidate = parent.resolve(preferred);
		int suffix = 2;
		while (Files.exists(candidate)) {
			candidate = parent.resolve(preferred + " (" + suffix++ + ")");
		}
		return candidate;
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (var walk = Files.walk(root)) {
			for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	/**
	 * Removes a mod by file name.
	 *
	 * <p>Removes only what was named. Deciding whether a dependency is now unused means
	 * knowing what else still wants it, and guessing wrong uninstalls a library three
	 * other mods needed.
	 */
	public boolean remove(String profileName, String fileName) throws IOException {
		Profile profile = this.home.loadProfile(profileName);

		List<Profile.Entry> remaining = profile.mods().stream()
				.filter(entry -> !entry.fileName().equalsIgnoreCase(fileName))
				.toList();

		if (remaining.size() == profile.mods().size()) {
			return false;
		}

		this.home.snapshot(profile, "remove " + fileName);
		profile.setMods(remaining);
		this.home.saveProfile(profile);
		this.profiles.materialise(profile);
		return true;
	}

	/** Turns a mod on or off without removing the file. */
	public boolean setEnabled(String profileName, String fileName, boolean enabled) throws IOException {
		Profile profile = this.home.loadProfile(profileName);

		boolean found = false;
		List<Profile.Entry> updated = new ArrayList<>(profile.mods().size());
		for (Profile.Entry entry : profile.mods()) {
			if (entry.fileName().equalsIgnoreCase(fileName)) {
				updated.add(entry.withEnabled(enabled));
				found = true;
			} else {
				updated.add(entry);
			}
		}

		if (!found) {
			return false;
		}

		profile.setMods(updated);
		this.home.saveProfile(profile);
		this.profiles.materialise(profile);
		return true;
	}

	/** The Fabric mod id inside a stored jar, or null if it isn't a Fabric mod. */
	private String readModId(String sha512) {
		try {
			return ModScanner.read(this.home.store().pathFor(sha512)).modId();
		} catch (IOException e) {
			return null;  // unreadable metadata just means we can't dedupe on it
		}
	}

	/** Where an entry with this mod id sits, or -1. Null ids never match each other. */
	private static int indexOfModId(List<Profile.Entry> entries, String modId) {
		if (modId == null || modId.isBlank()) {
			return -1;
		}
		for (int i = 0; i < entries.size(); i++) {
			if (modId.equalsIgnoreCase(entries.get(i).modId())) {
				return i;
			}
		}
		return -1;
	}
}
