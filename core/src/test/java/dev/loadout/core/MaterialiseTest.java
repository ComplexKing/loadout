package dev.loadout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.loadout.core.source.ContentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Staging a profile's files onto disk.
 *
 * <p>The part worth pinning down is which files end up in the generation Fabric is pointed
 * at, because that is the whole of what "this mod is off" means once the game starts.
 */
class MaterialiseTest {

	/** A file in the store, standing in for a downloaded jar. Contents only have to differ. */
	private static Profile.Entry store(LoadoutHome home, Path scratch, String fileName,
			boolean enabled, ContentType kind) throws IOException {
		Path file = scratch.resolve(fileName);
		Files.writeString(file, "bytes of " + fileName);

		String sha = home.store().put(file, null);
		return new Profile.Entry(sha, fileName, enabled, null, null, null, "modrinth", kind.key());
	}

	private static Set<String> namesIn(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) {
			return Set.of();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
		}
	}

	@Test
	@DisplayName("a disabled mod is left out of the generation entirely")
	void disabledModsAreNotStaged(@TempDir Path root, @TempDir Path scratch) throws IOException {
		LoadoutHome home = new LoadoutHome(root);
		ProfileManager profiles = new ProfileManager(home);

		List<Profile.Entry> entries = new ArrayList<>();
		entries.add(store(home, scratch, "sodium.jar", true, ContentType.MOD));
		entries.add(store(home, scratch, "noisy.jar", false, ContentType.MOD));

		Profile profile = new Profile("staging", "26.2", "fabric", entries);
		home.saveProfile(profile);
		profiles.materialise(profile);

		Path generation = new ModGenerations(home.profileDir("staging")).latest().orElseThrow();

		// Not "sodium.jar and noisy.jar.disabled": Fabric's addMods scan takes only names
		// ending in .jar, so a suffixed one would be listed at every startup as an
		// incompatible file for no reason. It stays recorded in the profile instead.
		assertEquals(Set.of("sodium.jar"), namesIn(generation));
		assertEquals(2, home.loadProfile("staging").mods().size());
	}

	@Test
	@DisplayName("turning a mod back on stages it again, in a new generation")
	void reEnablingRestages(@TempDir Path root, @TempDir Path scratch) throws IOException {
		LoadoutHome home = new LoadoutHome(root);
		ProfileManager profiles = new ProfileManager(home);

		Profile.Entry off = store(home, scratch, "sodium.jar", false, ContentType.MOD);
		Profile profile = new Profile("staging", "26.2", "fabric", List.of(off));
		home.saveProfile(profile);
		profiles.materialise(profile);

		ModGenerations generations = new ModGenerations(home.profileDir("staging"));
		Path first = generations.latest().orElseThrow();
		assertEquals(Set.of(), namesIn(first));

		profile.setMods(List.of(off.withEnabled(true)));
		profiles.materialise(profile);

		Path second = generations.latest().orElseThrow();
		// A new folder, because the running game holds the old one open and it can neither
		// be added to nor deleted while that is true.
		assertFalse(second.equals(first));
		assertEquals(Set.of("sodium.jar"), namesIn(second));
	}

	@Test
	@DisplayName("packs keep their own folders and are not swept into the generation")
	void packsGoToTheirOwnFolder(@TempDir Path root, @TempDir Path scratch) throws IOException {
		LoadoutHome home = new LoadoutHome(root);
		ProfileManager profiles = new ProfileManager(home);

		Profile profile = new Profile("staging", "26.2", "fabric", List.of(
				store(home, scratch, "faithful.zip", true, ContentType.RESOURCE_PACK),
				store(home, scratch, "sodium.jar", true, ContentType.MOD)));
		home.saveProfile(profile);
		profiles.materialise(profile);

		Path instance = home.profileDir("staging");
		assertEquals(Set.of("sodium.jar"),
				namesIn(new ModGenerations(instance).latest().orElseThrow()));
		assertTrue(namesIn(instance.resolve(ContentType.RESOURCE_PACK.folder()))
				.contains("faithful.zip"));
	}

	@Test
	@DisplayName("a hand-placed jar in mods/ is never touched")
	void handPlacedJarsSurvive(@TempDir Path root, @TempDir Path scratch) throws IOException {
		LoadoutHome home = new LoadoutHome(root);
		ProfileManager profiles = new ProfileManager(home);

		Path mods = home.profileDir("staging").resolve("mods");
		Files.createDirectories(mods);
		Files.writeString(mods.resolve("something-i-built.jar"), "mine");

		Profile profile = new Profile("staging", "26.2", "fabric",
				List.of(store(home, scratch, "sodium.jar", true, ContentType.MOD)));
		home.saveProfile(profile);
		profiles.materialise(profile);

		// Somebody who drops a jar in mods/ expects it to load, and Fabric still reads
		// that folder. Loadout stages alongside it rather than taking it over.
		assertTrue(Files.exists(mods.resolve("something-i-built.jar")));
	}
}
