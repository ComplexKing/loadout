package dev.loadout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.loadout.core.source.ContentType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * Snapshot ids, and the ordering the rest of the code reads them by.
 *
 * <p>Snapshots are taken automatically before anything destructive, which means several can
 * land inside one second: a rollback takes one immediately before restoring, and a scripted
 * run of installs gets there easily. The id is a timestamp accurate only to the second, so
 * without something more they would share a filename and each would replace the last --
 * losing exactly the state that was about to be undone.
 */
class SnapshotIdTest {

	private static Profile profileWith(String name, String... modHashes) {
		List<Profile.Entry> mods = new ArrayList<>();
		for (String hash : modHashes) {
			mods.add(new Profile.Entry(hash, hash + ".jar", true, null, null, null,
					"modrinth", ContentType.MOD.key()));
		}
		return new Profile(name, "26.2", "fabric", mods);
	}

	@Test
	@DisplayName("snapshots taken in the same second all survive, each with its own id")
	void noneAreOverwritten(@TempDir Path root) throws IOException {
		LoadoutHome home = new LoadoutHome(root);
		Profile profile = profileWith("play", "aaa");

		// Tight enough that most of these share a second, which is the case a stamp
		// accurate to the second gets wrong.
		Set<String> ids = new LinkedHashSet<>();
		for (int i = 0; i < 25; i++) {
			assertTrue(ids.add(home.snapshot(profile, "change " + i)),
					"an id was handed out twice");
		}

		List<Snapshot> stored = home.snapshots("play");
		assertEquals(25, stored.size(), "a snapshot was overwritten");
		assertEquals(ids, new LinkedHashSet<>(stored.stream().map(Snapshot::id).toList()));
	}

	@Test
	@DisplayName("each one keeps the profile it was taken of, not the last one written")
	void contentsAreNotClobbered(@TempDir Path root) throws IOException {
		LoadoutHome home = new LoadoutHome(root);

		// The failure that actually hurts: two snapshots in one second sharing a filename
		// means "before rollback" holds the state from after it.
		String before = home.snapshot(profileWith("play", "aaa"), "before rollback");
		String after = home.snapshot(profileWith("play", "aaa", "bbb"), "after rollback");

		List<Snapshot> stored = home.snapshots("play");
		Snapshot first = stored.stream().filter(s -> s.id().equals(before)).findFirst().orElseThrow();
		Snapshot second = stored.stream().filter(s -> s.id().equals(after)).findFirst().orElseThrow();

		assertEquals(1, first.profile().mods().size());
		assertEquals(2, second.profile().mods().size());
	}

	@Test
	@DisplayName("newest first, including within a single second")
	void newestFirst(@TempDir Path root) throws IOException {
		LoadoutHome home = new LoadoutHome(root);
		Profile profile = profileWith("play", "aaa");

		List<String> taken = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			taken.add(home.snapshot(profile, "change " + i));
		}

		List<String> read = home.snapshots("play").stream().map(Snapshot::id).toList();
		assertEquals(taken.reversed(), read);
	}

	@Test
	@DisplayName("the counter is fixed width, so the tenth does not sort before the second")
	void counterIsFixedWidth(@TempDir Path root) throws IOException {
		LoadoutHome home = new LoadoutHome(root);
		Profile profile = profileWith("play", "aaa");

		List<String> taken = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			taken.add(home.snapshot(profile, "change " + i));
		}

		// The ordering is plain string comparison, so an unpadded counter would put "-10"
		// between "-1" and "-2" and hand back a history in the wrong order.
		for (String id : taken) {
			assertTrue(id.matches(".*-\\d\\d$"), "not fixed width: " + id);
		}
		assertEquals(taken.reversed(), home.snapshots("play").stream().map(Snapshot::id).toList());
	}

	@Test
	@DisplayName("ids written before the counter existed still sort correctly beside new ones")
	void oldIdsStillOrder(@TempDir Path root) throws IOException {
		LoadoutHome home = new LoadoutHome(root);
		Path dir = root.resolve("snapshots").resolve("play");
		Files.createDirectories(dir);

		// An install that predates the counter has ids one second wide and three characters
		// shorter. They have to keep ordering against the new shape, because upgrading is
		// not a reason to hand somebody their history backwards.
		write(dir, "2026-08-26T21-46-31", "oldest, no counter");
		write(dir, "2026-08-26T21-46-32-00", "same second, first");
		write(dir, "2026-08-26T21-46-32-01", "same second, second");
		write(dir, "2026-08-26T21-46-33", "newest, no counter");

		List<String> reasons = home.snapshots("play").stream().map(Snapshot::reason).toList();
		assertEquals(List.of(
				"newest, no counter",
				"same second, second",
				"same second, first",
				"oldest, no counter"), reasons);
	}

	private static void write(Path dir, String id, String reason) throws IOException {
		String json = """
				{"id":"%s","takenAt":"2026-08-26T21:46:32Z","reason":"%s",
				 "profile":{"name":"play","minecraftVersion":"26.2","loader":"fabric","mods":[]}}
				""".formatted(id, reason);
		Files.writeString(dir.resolve(id + ".json"), json, StandardCharsets.UTF_8);
	}
}
