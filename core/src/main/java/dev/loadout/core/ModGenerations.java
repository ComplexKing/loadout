package dev.loadout.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A separate mods folder per launch, so the set can be changed while a game is running.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>Windows will not let a file be deleted while it is open, and Fabric holds every mod
 * jar open for as long as the game runs. Writing a new mod list into the folder the game
 * is using therefore fails -- which is why nothing could be staged mid-session, and why
 * starting a second game with a different set was impossible.
 *
 * <p>So each launch gets its own folder of hard links, and the game is pointed at it with
 * Fabric's own {@code fabric.addMods}. Changing the mod list writes a new folder and never
 * touches the one in use. Links cost nothing -- the bytes live once in the content store,
 * whatever number of generations point at them.
 *
 * <h2>What this does not take over</h2>
 *
 * <p>The instance's own {@code mods/} folder is left alone and still loaded by Fabric as
 * normal. Somebody who drops a jar in there expects it to work, and it does; Loadout
 * simply does not manage it. Managed mods and hand-placed ones therefore coexist rather
 * than one silently deleting the other, which is what a shared folder would have meant.
 */
public final class ModGenerations {
	/** Kept out of the way, since it is bookkeeping rather than something to browse. */
	private static final String ROOT = ".loadout";
	private static final String PREFIX = "gen-";

	/**
	 * How many old generations to keep.
	 *
	 * <p>More than one because a generation may still be in use by a game that is running,
	 * or by one that is starting. Not many more, because each is a folder of links and
	 * they are only interesting until the next launch.
	 */
	private static final int KEEP = 3;

	private final Path instance;

	public ModGenerations(Path instanceDir) {
		this.instance = instanceDir;
	}

	/**
	 * Where a new generation should be written. Always an empty folder that did not exist.
	 *
	 * <p>Named by time so the folders sort chronologically, with a counter because a clock
	 * in milliseconds is not fine enough to separate them. Toggling several mods at once
	 * restages several times in a row and lands inside one millisecond easily -- and
	 * reusing a folder would leave the earlier staging's links in it, so a mod switched
	 * off would still be there to load. The counter is fixed width so the plain string
	 * ordering the rest of this class relies on stays chronological.
	 */
	public Path create() throws IOException {
		long now = System.currentTimeMillis();
		Path root = root();
		Files.createDirectories(root);

		for (int counter = 0; counter < 100; counter++) {
			Path dir = root.resolve(String.format("%s%013d-%02d", PREFIX, now, counter));
			try {
				// createDirectory, not createDirectories: this one has to fail if the
				// folder is already there, which is the whole point of the loop.
				return Files.createDirectory(dir);
			} catch (java.nio.file.FileAlreadyExistsException e) {
				// Taken by a staging a moment ago. Try the next.
			}
		}

		// A hundred in one millisecond is not restaging, it is a loop somewhere.
		throw new IOException("Could not find a free mod generation under " + root);
	}

	/** The most recent generation, or empty when none has been written yet. */
	public java.util.Optional<Path> latest() throws IOException {
		List<Path> all = all();
		return all.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(all.get(0));
	}

	/** Newest first. */
	public List<Path> all() throws IOException {
		Path root = root();
		if (!Files.isDirectory(root)) {
			return List.of();
		}

		try (Stream<Path> entries = Files.list(root)) {
			List<Path> found = new ArrayList<>(entries
					.filter(Files::isDirectory)
					.filter(path -> path.getFileName().toString().startsWith(PREFIX))
					.toList());

			found.sort(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());
			return List.copyOf(found);
		}
	}

	/**
	 * Deletes generations beyond the few most recent.
	 *
	 * <p>A generation still open by a running game cannot be deleted on Windows, and that
	 * is exactly the right outcome: the attempt fails, the folder stays, and it is cleaned
	 * up next time when nothing holds it. So a failure here is ignored rather than
	 * reported -- it means the safety worked.
	 */
	public void prune() throws IOException {
		List<Path> all = all();
		for (int i = KEEP; i < all.size(); i++) {
			deleteQuietly(all.get(i));
		}
	}

	private static void deleteQuietly(Path dir) {
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					// In use. Leave it; the next prune will find it free.
					return;
				}
			}
		} catch (IOException e) {
			// Already gone, or unreadable. Either way there is nothing to do.
		}
	}

	private Path root() {
		return this.instance.resolve(ROOT);
	}

	// -- the running marker ------------------------------------------------------------

	/**
	 * Note that a game is running, so a launcher restarted underneath it still knows.
	 *
	 * <p>Without this, closing and reopening Loadout while playing showed Play again --
	 * and pressing it starts a second game on the same files, with two Fabric instances
	 * holding the same jars and two copies of a world's session lock. That is one misclick
	 * from a mess, so the fact outlives the process that knew it.
	 */
	public void markRunning(long pid) throws IOException {
		Path file = runningMarker();
		Files.createDirectories(file.getParent());
		Files.writeString(file, Long.toString(pid));
	}

	public void clearRunning() {
		try {
			Files.deleteIfExists(runningMarker());
		} catch (IOException e) {
			// Left behind at worst, and the pid check below sees through a stale one.
		}
	}

	/**
	 * Whether a game is up, according to the marker and the operating system.
	 *
	 * <p>The pid is checked rather than trusted. A launcher killed alongside its game
	 * leaves the marker behind, and a file saying "running" forever would be worse than no
	 * file at all -- so the answer comes from whether that process actually still exists.
	 */
	public boolean isRunning() {
		try {
			Path file = runningMarker();
			if (!Files.isRegularFile(file)) {
				return false;
			}

			long pid = Long.parseLong(Files.readString(file).trim());
			return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
		} catch (IOException | NumberFormatException e) {
			return false;
		}
	}

	private Path runningMarker() {
		return root().resolve("running.pid");
	}
}
