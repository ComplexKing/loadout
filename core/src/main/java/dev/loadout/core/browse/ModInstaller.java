package dev.loadout.core.browse;

import dev.loadout.core.Downloader;
import dev.loadout.core.LoadoutHome;
import dev.loadout.core.ModrinthVersion;
import dev.loadout.core.Profile;
import dev.loadout.core.ProfileManager;
import dev.loadout.core.ModScanner;
import java.io.IOException;
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
	private final ModBrowser browser;
	private final Downloader downloader;

	public ModInstaller(LoadoutHome home) {
		this.home = home;
		this.profiles = new ProfileManager(home);
		this.browser = new ModBrowser();
		this.downloader = new Downloader(home.store());
	}

	/**
	 * @param installed what was added, in the order resolved
	 * @param alreadyPresent projects the profile already had
	 * @param unavailable projects with no build for this profile's Minecraft version
	 */
	public record Result(
			List<String> installed,
			List<String> alreadyPresent,
			List<String> unavailable,
			List<String> upgraded
	) {
		public boolean changedAnything() {
			return !this.installed.isEmpty();
		}
	}

	/**
	 * Installs a mod and everything it requires.
	 *
	 * <p>Dependencies are resolved breadth-first and transitively, because a library
	 * frequently depends on another one. Installing only what the user asked for produces
	 * a profile that fails at startup with a missing-dependency error, which is the single
	 * most common way a hand-managed mods folder breaks.
	 *
	 * <p>Nothing is written until every file has downloaded and verified, so a failure
	 * partway leaves the profile exactly as it was.
	 */
	public Result install(String profileName, String projectId, Downloader.Progress progress)
			throws IOException, InterruptedException {
		Profile profile = this.home.loadProfile(profileName);
		String gameVersion = profile.minecraftVersion();
		String loader = profile.loader();

		Set<String> present = new HashSet<>();
		for (Profile.Entry entry : profile.mods()) {
			if (entry.projectId() != null) {
				present.add(entry.projectId());
			}
		}

		List<String> installedNames = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		List<String> unavailable = new ArrayList<>();
		Map<String, ModrinthVersion> toAdd = new LinkedHashMap<>();

		Deque<String> queue = new ArrayDeque<>();
		Set<String> seen = new HashSet<>();
		queue.add(projectId);
		seen.add(projectId);

		while (!queue.isEmpty()) {
			String current = queue.poll();

			if (present.contains(current)) {
				skipped.add(this.browser.projectTitle(current));
				continue;
			}

			Optional<ModrinthVersion> version = this.browser.bestVersion(current, gameVersion, loader);
			if (version.isEmpty()) {
				// A dependency with no build for this version is a hard stop for the mod
				// that wanted it, but worth reporting rather than failing silently.
				unavailable.add(this.browser.projectTitle(current));
				continue;
			}

			toAdd.put(current, version.get());

			for (String dependency : this.browser.requiredDependencies(version.get().versionId())) {
				if (seen.add(dependency)) {
					queue.add(dependency);
				}
			}
		}

		if (toAdd.isEmpty()) {
			return new Result(List.of(), List.copyOf(skipped), List.copyOf(unavailable), List.of());
		}

		// Everything downloads into the content store first. That touches nothing the
		// profile can see, so this stage is safe to fail.
		Map<String, String> hashes = new LinkedHashMap<>();
		for (Map.Entry<String, ModrinthVersion> entry : toAdd.entrySet()) {
			hashes.put(entry.getKey(), this.downloader.fetch(entry.getValue(), progress));
		}

		this.home.snapshot(profile, "install " + this.browser.projectTitle(projectId));

		List<Profile.Entry> updated = new ArrayList<>(profile.mods());
		List<String> upgraded = new ArrayList<>();

		for (Map.Entry<String, ModrinthVersion> entry : toAdd.entrySet()) {
			ModrinthVersion version = entry.getValue();
			String hash = hashes.get(entry.getKey());

			// Read the id out of the jar we just stored. This is the check that actually
			// prevents a broken profile: Fabric refuses to start with two mods declaring
			// the same id, and a Modrinth project id can't catch it -- the same mod
			// obtained from CurseForge or built locally has no project id at all.
			String modId = readModId(hash);

			int existing = indexOfModId(updated, modId);
			Profile.Entry replacement = new Profile.Entry(
					hash, version.fileName(), true, entry.getKey(), version.versionNumber(), modId);

			if (existing >= 0) {
				// Already have this mod at another version. Replacing is what the user
				// meant; adding would leave two copies and a game that won't launch.
				upgraded.add(updated.get(existing).fileName() + " -> " + version.fileName());
				updated.set(existing, replacement.withEnabled(updated.get(existing).enabled()));
			} else {
				updated.add(replacement);
				installedNames.add(version.fileName());
			}
		}

		profile.setMods(updated);
		this.home.saveProfile(profile);
		this.profiles.materialise(profile);

		return new Result(List.copyOf(installedNames), List.copyOf(skipped),
				List.copyOf(unavailable), List.copyOf(upgraded));
	}

	/**
	 * Removes a mod by file name.
	 *
	 * <p>Removes only what was named. Working out whether a dependency is now unused
	 * means knowing what else still wants it, and guessing wrong uninstalls a library
	 * three other mods needed — so anything orphaned is left for {@code prune} to report
	 * rather than silently deleted here.
	 *
	 * @return true if something was removed
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

	public ModBrowser browser() {
		return this.browser;
	}
}
