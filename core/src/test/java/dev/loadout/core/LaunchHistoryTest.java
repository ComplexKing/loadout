package dev.loadout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.loadout.core.source.ContentType;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deciding whether a launch failed, and whether the mods are why.
 *
 * <p>The verdict is a guess made from two facts: whether the game ever said it was up, and
 * how long it lasted. Every branch below is a case somebody will actually hit, and getting
 * one wrong means either a rollback offered for a crash that had nothing to do with mods,
 * or silence when somebody's game genuinely stopped starting.
 */
class LaunchHistoryTest {

	/** A clock that only moves when told, so a minute costs nothing to test. */
	private static final class Hand extends Clock {
		private Instant now = Instant.parse("2026-08-26T12:00:00Z");

		@Override
		public Instant instant() {
			return this.now;
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		void advance(Duration by) {
			this.now = this.now.plus(by);
		}
	}

	/** Entries need a hash and nothing else; these never touch the store. */
	private static Profile profileWith(String name, String... modHashes) {
		List<Profile.Entry> mods = new ArrayList<>();
		for (String hash : modHashes) {
			mods.add(new Profile.Entry(hash, hash + ".jar", true, null, null, null,
					"modrinth", ContentType.MOD.key()));
		}
		return new Profile(name, "26.2", "fabric", mods);
	}

	@Test
	@DisplayName("nothing has been launched yet, so there is nothing to say")
	void noHistory(@TempDir Path root) throws IOException {
		LaunchHistory history = new LaunchHistory(new LoadoutHome(root));

		assertEquals(LaunchHistory.Diagnosis.FINE, history.diagnose("fresh"));
	}

	@Test
	@DisplayName("a game that is still running is not a game that failed")
	void stillRunning(@TempDir Path root) throws IOException {
		LoadoutHome home = new LoadoutHome(root);
		LaunchHistory history = new LaunchHistory(home);

		history.started(profileWith("play", "aaa"));

		// No exit code yet. Reporting a failure here would flash a rollback offer at
		// somebody every single time they pressed Play.
		assertFalse(history.diagnose("play").failedToStart());
	}

	@Test
	@DisplayName("a game that reported in and then crashed hours later is not a mod problem")
	void crashedLongAfterStarting(@TempDir Path root) throws IOException {
		Hand clock = new Hand();
		LoadoutHome home = new LoadoutHome(root);
		LaunchHistory history = new LaunchHistory(home, clock);

		Profile profile = profileWith("play", "aaa");
		home.saveProfile(profile);

		history.started(profile);
		history.healthy(profile);
		clock.advance(Duration.ofHours(2));
		history.finished("play", 1);

		// It started. Rolling the mod list back is the one thing that certainly would not
		// have helped, so it must not be offered.
		LaunchHistory.Diagnosis diagnosis = history.diagnose("play");
		assertFalse(diagnosis.failedToStart());
		assertNull(diagnosis.rollbackTo());
	}

	@Test
	@DisplayName("a bad exit within the first minute, after the mods changed, offers the last good set")
	void failedStartOffersRollback(@TempDir Path root) throws IOException {
		Hand clock = new Hand();
		LoadoutHome home = new LoadoutHome(root);
		LaunchHistory history = new LaunchHistory(home, clock);

		Profile working = profileWith("play", "aaa");
		home.saveProfile(working);
		history.started(working);
		String snapshotId = history.healthy(working).orElseThrow();
		clock.advance(Duration.ofMinutes(30));
		history.finished("play", 0);

		// Somebody adds a mod, and it does not start.
		clock.advance(Duration.ofMinutes(1));
		Profile broken = profileWith("play", "aaa", "bbb");
		home.saveProfile(broken);
		history.started(broken);
		clock.advance(Duration.ofSeconds(9));
		history.finished("play", 1);

		LaunchHistory.Diagnosis diagnosis = history.diagnose("play");
		assertTrue(diagnosis.failedToStart());
		assertEquals(snapshotId, diagnosis.rollbackTo());
		assertNotNull(diagnosis.detail());
	}

	@Test
	@DisplayName("the same mods that worked before means the mods are not what changed")
	void sameSetOffersNothing(@TempDir Path root) throws IOException {
		Hand clock = new Hand();
		LoadoutHome home = new LoadoutHome(root);
		LaunchHistory history = new LaunchHistory(home, clock);

		Profile profile = profileWith("play", "aaa");
		home.saveProfile(profile);
		history.started(profile);
		history.healthy(profile);
		clock.advance(Duration.ofMinutes(5));
		history.finished("play", 0);

		// Unchanged, and now it will not start -- a driver, a Java update, a full disk.
		history.started(profile);
		clock.advance(Duration.ofSeconds(4));
		history.finished("play", 1);

		LaunchHistory.Diagnosis diagnosis = history.diagnose("play");
		assertTrue(diagnosis.failedToStart());
		// Offering a rollback that changes nothing would send somebody looking in the
		// wrong place for as long as they believed it.
		assertNull(diagnosis.rollbackTo());
	}

	@Test
	@DisplayName("without a companion mod, a clean exit is still a clean exit")
	void noReportButExitedZero(@TempDir Path root) throws IOException {
		Hand clock = new Hand();
		LaunchHistory history = new LaunchHistory(new LoadoutHome(root), clock);

		history.started(profileWith("play", "aaa"));
		clock.advance(Duration.ofSeconds(3));
		history.finished("play", 0);

		// Three seconds and a zero exit is somebody closing the window immediately. Not
		// something to raise, and the fallback must not read it as a crash.
		assertFalse(history.diagnose("play").failedToStart());
	}

	@Test
	@DisplayName("a failed start with nothing known to work says so instead of staying quiet")
	void noGoodSetYet(@TempDir Path root) throws IOException {
		Hand clock = new Hand();
		LaunchHistory history = new LaunchHistory(new LoadoutHome(root), clock);

		history.started(profileWith("play", "aaa"));
		clock.advance(Duration.ofSeconds(6));
		history.finished("play", 1);

		LaunchHistory.Diagnosis diagnosis = history.diagnose("play");
		assertTrue(diagnosis.failedToStart());
		assertNull(diagnosis.rollbackTo());
		assertNotNull(diagnosis.detail());
	}

	@Test
	@DisplayName("a snapshot is taken the first time a set works, and not again")
	void snapshotsOnlyOnChange(@TempDir Path root) throws IOException {
		LoadoutHome home = new LoadoutHome(root);
		LaunchHistory history = new LaunchHistory(home);

		Profile profile = profileWith("play", "aaa");
		home.saveProfile(profile);

		history.started(profile);
		assertTrue(history.healthy(profile).isPresent());

		// A hundred launches of an unchanged pack would otherwise bury the useful
		// snapshots under a hundred identical ones.
		history.started(profile);
		assertTrue(history.healthy(profile).isEmpty());
		assertEquals(1, home.snapshots("play").size());
	}

	@Test
	@DisplayName("the fingerprint ignores order and disabled mods, and notices a version change")
	void fingerprintNoticesWhatMatters() {
		String a = LaunchHistory.fingerprint(profileWith("play", "aaa", "bbb"));

		// Reordering a list is not a change to what loads.
		assertEquals(a, LaunchHistory.fingerprint(profileWith("play", "bbb", "aaa")));

		// A mod that is off cannot be why the game will not start.
		Profile withDisabled = profileWith("play", "aaa", "bbb");
		withDisabled.setMods(List.of(
				withDisabled.mods().get(0),
				withDisabled.mods().get(1),
				new Profile.Entry("ccc", "ccc.jar", false, null, null, null,
						"modrinth", ContentType.MOD.key())));
		assertEquals(a, LaunchHistory.fingerprint(withDisabled));

		// A different version of the same mod is a different hash, and is exactly the
		// change worth noticing.
		assertNotEquals(a, LaunchHistory.fingerprint(profileWith("play", "aaa", "bbb2")));

		// Packs live in the same list and cannot stop the game loading.
		Profile withPack = profileWith("play", "aaa", "bbb");
		List<Profile.Entry> mods = new ArrayList<>(withPack.mods());
		mods.add(new Profile.Entry("ddd", "faithful.zip", true, null, null, null,
				"modrinth", ContentType.RESOURCE_PACK.key()));
		withPack.setMods(mods);
		assertEquals(a, LaunchHistory.fingerprint(withPack));
	}
}
