package dev.loadout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-launch mod folders.
 *
 * <p>The reason these exist at all: Windows refuses to delete a file a process holds open,
 * and Fabric holds every mod jar open for the life of the game. Staging into the folder
 * the running game loaded from therefore cannot work, so each launch gets its own.
 */
class ModGenerationsTest {

	@Test
	@DisplayName("a generation is never handed out twice, even inside one millisecond")
	void alwaysFresh(@TempDir Path instance) throws IOException {
		ModGenerations generations = new ModGenerations(instance);

		// Tight enough that many of these land in the same millisecond, which is exactly
		// the case a clock-named folder gets wrong. Toggling a handful of mods restages
		// once per toggle and gets here just as fast.
		Set<Path> handedOut = new LinkedHashSet<>();
		for (int i = 0; i < 40; i++) {
			Path dir = generations.create();
			assertTrue(handedOut.add(dir), "handed out " + dir + " twice");
		}
	}

	@Test
	@DisplayName("a fresh generation is empty, so a stale link cannot survive into it")
	void startsEmpty(@TempDir Path instance) throws IOException {
		ModGenerations generations = new ModGenerations(instance);

		Path first = generations.create();
		Files.writeString(first.resolve("something.jar"), "x");

		// The failure this guards: reusing a folder would leave the previous staging's
		// links in place, and a mod switched off would still be sitting there to load.
		for (int i = 0; i < 5; i++) {
			Path next = generations.create();
			try (var entries = Files.list(next)) {
				assertEquals(List.of(), entries.toList(), next + " was not empty");
			}
		}
	}

	@Test
	@DisplayName("newest first, whatever order they were made in")
	void newestFirst(@TempDir Path instance) throws IOException {
		ModGenerations generations = new ModGenerations(instance);

		List<Path> made = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			made.add(generations.create());
		}

		List<Path> all = generations.all();
		assertEquals(made.reversed(), all);
		assertEquals(made.getLast(), generations.latest().orElseThrow());
	}

	@Test
	@DisplayName("nothing staged yet is not an error")
	void emptyBeforeAnyLaunch(@TempDir Path instance) throws IOException {
		ModGenerations generations = new ModGenerations(instance);

		assertEquals(List.of(), generations.all());
		assertTrue(generations.latest().isEmpty());
		generations.prune();   // must not throw on a folder that was never created
	}

	@Test
	@DisplayName("pruning keeps the recent few and drops the rest")
	void pruneKeepsRecent(@TempDir Path instance) throws IOException {
		ModGenerations generations = new ModGenerations(instance);

		List<Path> made = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			made.add(generations.create());
		}

		generations.prune();

		List<Path> left = generations.all();
		assertTrue(left.size() <= 4, "kept " + left.size());
		// Whatever else goes, the one a launch would pick has to survive.
		assertTrue(left.contains(made.getLast()));
		assertFalse(Files.exists(made.getFirst()), "the oldest should be gone");
	}
}
