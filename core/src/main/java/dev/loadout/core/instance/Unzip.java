package dev.loadout.core.instance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracting an archive downloaded from a registry.
 *
 * <p>Worlds are published as zips and have to be unpacked to be playable, unlike mods and
 * packs which the game reads as files. That makes this the one place Loadout writes
 * arbitrary paths chosen by a stranger, so it is also the one place that has to refuse
 * them.
 */
public final class Unzip {
	/**
	 * A ceiling on what one archive may expand to.
	 *
	 * <p>Compression ratios of a thousand to one are easy to construct, so a small download
	 * can fill a disk. The limit is generous for a real world and nowhere near what a zip
	 * bomb needs.
	 */
	private static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024;

	private static final int MAX_ENTRIES = 100_000;

	private Unzip() {
	}

	/**
	 * @param archive the zip to read
	 * @param target where its contents should end up
	 * @return how many files were written
	 */
	public static int into(Path archive, Path target) throws IOException {
		Path root = target.toAbsolutePath().normalize();
		Files.createDirectories(root);

		long total = 0;
		int written = 0;

		try (ZipFile zip = new ZipFile(archive.toFile())) {
			var entries = zip.entries();
			int seen = 0;

			while (entries.hasMoreElements()) {
				if (++seen > MAX_ENTRIES) {
					throw new IOException("Archive has an implausible number of entries");
				}

				ZipEntry entry = entries.nextElement();
				Path destination = resolveSafely(root, entry.getName());

				if (entry.isDirectory()) {
					Files.createDirectories(destination);
					continue;
				}

				Files.createDirectories(destination.getParent());
				try (InputStream in = zip.getInputStream(entry)) {
					total += Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
				}

				if (total > MAX_TOTAL_BYTES) {
					throw new IOException("Archive expands to more than 4 GB; refusing to continue");
				}
				written++;
			}
		}

		return written;
	}

	/**
	 * Resolves an entry name inside the target, refusing anything that escapes it.
	 *
	 * <p>Zip entry names are arbitrary strings written by whoever built the archive, and
	 * "../../../../etc/passwd" is a perfectly legal one. Resolving and then checking the
	 * normalised result stays inside the target is what makes that name an error rather
	 * than a file written wherever it asked.
	 */
	private static Path resolveSafely(Path root, String name) throws IOException {
		if (name.contains("\0")) {
			throw new IOException("Archive entry name contains a null byte");
		}

		Path resolved = root.resolve(name).normalize().toAbsolutePath();
		if (!resolved.startsWith(root)) {
			throw new IOException("Archive entry would be written outside the target: " + name);
		}
		return resolved;
	}

	/**
	 * Where a world archive's contents actually begin.
	 *
	 * <p>Most are published wrapped in a folder, some are not, and the game needs the
	 * folder that directly contains level.dat. Guessing wrong produces a saves entry the
	 * game silently ignores, which is a confusing way to fail.
	 *
	 * @return the directory holding level.dat, or null if there is none
	 */
	public static Path findWorldRoot(Path extracted) throws IOException {
		if (Files.isRegularFile(extracted.resolve("level.dat"))) {
			return extracted;
		}

		try (var walk = Files.walk(extracted, 3)) {
			return walk.filter(path -> path.getFileName().toString().equals("level.dat"))
					.map(Path::getParent)
					.findFirst()
					.orElse(null);
		}
	}
}
