package dev.loadout.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.loadout.core.source.ContentType;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Whether the last launch of a profile actually got anywhere, and what to go back to.
 *
 * <h2>Why this is worth keeping</h2>
 *
 * <p>The failure this exists for is specific and common: somebody adds a mod, the game no
 * longer starts, and the log is four thousand lines that all look equally suspicious. The
 * fact that would fix it is not in that log -- it is that the previous mod set worked.
 * Snapshots already hold those sets; nothing was recording which of them were good.
 *
 * <h2>What counts as having started</h2>
 *
 * <p>The companion mod reports in once the client is up. That is the strong signal: Fabric
 * resolved the mod list, every mixin applied, and the window exists. Nothing the launcher
 * can see from outside the process means as much.
 *
 * <p>Without it -- no companion mod, or an older one -- the fallback is a non-zero exit
 * inside the first minute. Crude, but it separates the case worth acting on from the one
 * that is not: a game that ran for an hour and then crashed did start, and rolling its mod
 * list back would be answering a question nobody asked.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It does not roll anything back. Rewriting somebody's mod list because a process
 * exited badly is the kind of help that is indistinguishable from a bug, and the diagnosis
 * here is a guess -- a good one, but a guess. So this reports, and a person decides.
 */
public final class LaunchHistory {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * How long a game has to survive before a bad exit stops looking like a failed start.
	 *
	 * <p>Generous on purpose. A heavy pack on a cold file cache can spend most of a minute
	 * before the menu appears, and calling that a crash would be worse than missing one.
	 */
	private static final Duration STARTUP_WINDOW = Duration.ofSeconds(60);

	private final LoadoutHome home;
	private final java.time.Clock clock;

	public LaunchHistory(LoadoutHome home) {
		this(home, java.time.Clock.systemUTC());
	}

	/**
	 * Injectable time, because how long a game ran is half of the verdict.
	 *
	 * <p>Only for tests. Waiting out a real minute to check the boundary would make the
	 * suite slower than every other test put together, and the branch would end up untested
	 * for exactly that reason.
	 */
	LaunchHistory(LoadoutHome home, java.time.Clock clock) {
		this.home = home;
		this.clock = clock;
	}

	private Instant now() {
		return this.clock.instant();
	}

	/**
	 * @param fingerprint identifies the enabled mod set, so "the same mods as last time"
	 *     can be told from "a different set that happens to be the same size"
	 * @param healthy whether the game reported that it had finished starting
	 * @param exitCode null while it is still running
	 */
	public record Attempt(
			String startedAt,
			String fingerprint,
			boolean healthy,
			Integer exitCode,
			long ranForMs
	) {
	}

	/** @param snapshotId the snapshot holding the mod set that last started cleanly */
	public record Known(String snapshotId, String fingerprint, String at) {
	}

	private record Stored(Known lastGood, Attempt last) {
	}

	/**
	 * What to tell somebody about the last launch.
	 *
	 * @param failedToStart the game did not get far enough to be usable
	 * @param rollbackTo a snapshot worth offering, or null when there is nothing useful to
	 *     offer -- including when the set that failed is the same one that worked before,
	 *     which means the mods are not what changed
	 */
	public record Diagnosis(boolean failedToStart, String rollbackTo, String detail) {
		public static final Diagnosis FINE = new Diagnosis(false, null, null);
	}

	// -- writing ---------------------------------------------------------------------

	/** Called as a launch begins. Clears the previous verdict so it cannot be read as this one. */
	public void started(Profile profile) throws IOException {
		Stored stored = read(profile.name());
		write(profile.name(), new Stored(stored.lastGood(),
				new Attempt(now().toString(), fingerprint(profile), false, null, 0)));
	}

	/**
	 * Called when the running game says it has finished starting.
	 *
	 * <p>Takes a snapshot the first time a given set gets here, so there is something
	 * concrete to roll back to later. Only on a change: a snapshot per launch would bury
	 * the useful ones under a hundred identical copies.
	 *
	 * @return the snapshot taken, or empty if this set was already known good
	 */
	public Optional<String> healthy(Profile profile) throws IOException {
		String name = profile.name();
		Stored stored = read(name);
		String fingerprint = fingerprint(profile);

		Attempt last = stored.last() == null
				? new Attempt(now().toString(), fingerprint, true, null, 0)
				: new Attempt(stored.last().startedAt(), fingerprint, true,
						stored.last().exitCode(), stored.last().ranForMs());

		if (stored.lastGood() != null && fingerprint.equals(stored.lastGood().fingerprint())) {
			write(name, new Stored(stored.lastGood(), last));
			return Optional.empty();
		}

		String snapshotId = this.home.snapshot(profile, "started cleanly");
		write(name, new Stored(new Known(snapshotId, fingerprint, now().toString()), last));
		return Optional.of(snapshotId);
	}

	/** Called once the game's process is gone. */
	public void finished(String profileName, int exitCode) throws IOException {
		Stored stored = read(profileName);
		if (stored.last() == null) {
			return;   // nothing was recorded as started, so there is no attempt to close
		}

		long ranFor;
		try {
			ranFor = Duration.between(Instant.parse(stored.last().startedAt()), now()).toMillis();
		} catch (RuntimeException e) {
			ranFor = 0;   // unparseable stamp from an older file; the exit code still counts
		}

		write(profileName, new Stored(stored.lastGood(), new Attempt(
				stored.last().startedAt(), stored.last().fingerprint(),
				stored.last().healthy(), exitCode, ranFor)));
	}

	// -- reading ---------------------------------------------------------------------

	public Diagnosis diagnose(String profileName) throws IOException {
		Stored stored = read(profileName);
		Attempt last = stored.last();

		if (last == null || last.exitCode() == null) {
			return Diagnosis.FINE;   // never launched, or still running
		}
		if (last.healthy()) {
			// It reached the menu. Whatever went wrong afterwards, it was not the mod list
			// failing to load, and that is the only thing a rollback would fix.
			return Diagnosis.FINE;
		}
		if (last.exitCode() == 0) {
			return Diagnosis.FINE;   // closed normally, just with no companion mod to say so
		}
		if (last.ranForMs() >= STARTUP_WINDOW.toMillis()) {
			return new Diagnosis(false, null, "The game closed unexpectedly after "
					+ Duration.ofMillis(last.ranForMs()).toMinutes() + " minutes.");
		}

		Known good = stored.lastGood();
		if (good == null) {
			return new Diagnosis(true, null,
					"The game did not finish starting, and no earlier set is known to work.");
		}
		if (good.fingerprint().equals(last.fingerprint())) {
			// The same mods that worked before. Rolling back would change nothing, and
			// offering it would send somebody looking in the wrong place.
			return new Diagnosis(true, null,
					"The game did not finish starting, but these are the same mods that started "
							+ "last time -- so something else changed.");
		}

		return new Diagnosis(true, good.snapshotId(),
				"The game did not finish starting. The mods have changed since the last set "
						+ "that did.");
	}

	public Optional<Known> lastGood(String profileName) throws IOException {
		return Optional.ofNullable(read(profileName).lastGood());
	}

	public Optional<Attempt> last(String profileName) throws IOException {
		return Optional.ofNullable(read(profileName).last());
	}

	// -- the fingerprint --------------------------------------------------------------

	/**
	 * Identifies a mod set by what is switched on, not by how it is written down.
	 *
	 * <p>Sorted, so reordering the list is not a change. Disabled entries left out, because
	 * a mod that is off cannot be why the game will not start. Hashes rather than names,
	 * because updating a mod to a new version is exactly the change worth noticing.
	 */
	public static String fingerprint(Profile profile) {
		List<String> hashes = new ArrayList<>();
		for (Profile.Entry entry : profile.mods()) {
			if (entry.enabled() && entry.kind() == ContentType.MOD) {
				hashes.add(entry.sha512().toLowerCase(Locale.ROOT));
			}
		}
		hashes.sort(null);

		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String hash : hashes) {
				digest.update(hash.getBytes(StandardCharsets.UTF_8));
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required of every JVM", e);
		}
	}

	// -- storage ----------------------------------------------------------------------

	private Path fileFor(String profileName) {
		return this.home.root().resolve("history")
				.resolve(LoadoutHome.requireValidName(profileName) + ".json");
	}

	private Stored read(String profileName) throws IOException {
		Path file = fileFor(profileName);
		if (!Files.isRegularFile(file)) {
			return new Stored(null, null);
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Stored stored = GSON.fromJson(reader, Stored.class);
			return stored == null ? new Stored(null, null) : stored;
		} catch (JsonParseException e) {
			// History is a convenience, not something anyone depends on. A corrupt file is
			// worth forgetting rather than worth failing a launch over.
			return new Stored(null, null);
		}
	}

	private void write(String profileName, Stored stored) throws IOException {
		Path file = fileFor(profileName);
		Files.createDirectories(file.getParent());
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(stored, writer);
		}
	}
}
