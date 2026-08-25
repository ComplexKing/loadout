package dev.loadout.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * One shared pool holding every mod file, addressed by its own hash.
 *
 * <p>Profiles don't own their jars; they hold hard links into this store. Several things
 * that are normally separate features fall out of that one decision:
 *
 * <ul>
 *   <li><b>Dedup.</b> Ten profiles using Sodium reference one file on disk, not ten.
 *       Modded setups are mostly the same handful of libraries repeated.
 *   <li><b>Instant rollback.</b> A snapshot is a list of hashes, so restoring one is
 *       relinking, not re-downloading. Undoing a botched migration costs nothing and
 *       works offline.
 *   <li><b>Nothing is ever half-written.</b> Files land under their own hash, so a file
 *       that exists is a file that verified.
 * </ul>
 *
 * <p>Hard links, not copies or symlinks: a hard link costs one directory entry, needs no
 * elevated permissions on Windows the way symlinks do, and looks like an ordinary file to
 * Minecraft. The catch is that they can't cross a volume, so this falls back to copying
 * when a profile lives on a different drive from the store.
 */
public final class ModStore {
	/** Two-character shard, so no single directory ends up with thousands of entries. */
	private static final int SHARD_LENGTH = 2;

	private final Path root;

	public ModStore(Path root) {
		this.root = root;
	}

	public Path root() {
		return this.root;
	}

	/** Where a hash lives, whether or not it's there yet. */
	public Path pathFor(String sha512) {
		String hash = sha512.toLowerCase();
		return this.root.resolve(hash.substring(0, SHARD_LENGTH)).resolve(hash + ".jar");
	}

	public boolean has(String sha512) {
		return Files.isRegularFile(pathFor(sha512));
	}

	/**
	 * Takes a file into the store.
	 *
	 * <p>Verifies the content rather than trusting the caller's hash: this is the only
	 * gate between a download and every profile that will link to it, and a corrupted
	 * jar propagating silently would be far worse than an error here.
	 *
	 * @param expectedSha512 the hash the file is claimed to have, or null to accept whatever it is
	 * @return the hash it's stored under
	 * @throws IOException if the file doesn't match the hash it was supposed to have
	 */
	public String put(Path file, String expectedSha512) throws IOException {
		String actual = ModScanner.sha512(file);
		if (expectedSha512 != null && !actual.equalsIgnoreCase(expectedSha512)) {
			throw new IOException("Hash mismatch for " + file.getFileName()
					+ ": expected " + expectedSha512.substring(0, 16) + "..."
					+ " but got " + actual.substring(0, 16) + "...");
		}

		Path destination = pathFor(actual);
		if (Files.isRegularFile(destination)) {
			return actual;  // already have it; content addressing means it's identical
		}

		Files.createDirectories(destination.getParent());

		// Land it under a temporary name and move into place, so a crash mid-write can
		// never leave a truncated file sitting at a hash that claims to be complete.
		Path temp = destination.resolveSibling(destination.getFileName() + ".incoming");
		Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING);
		try {
			Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			// Another process may have stored the same content first, which is fine --
			// identical bytes by definition.
			Files.deleteIfExists(temp);
			if (!Files.isRegularFile(destination)) {
				throw e;
			}
		}

		return actual;
	}

	/**
	 * Materialises a stored file into a profile.
	 *
	 * @param enabled false to write it with the {@code .disabled} suffix Fabric ignores
	 */
	public void linkInto(String sha512, Path destinationDir, String fileName, boolean enabled) throws IOException {
		Path source = pathFor(sha512);
		if (!Files.isRegularFile(source)) {
			throw new IOException("Not in the store: " + sha512.substring(0, 16) + "...");
		}

		Files.createDirectories(destinationDir);
		Path destination = destinationDir.resolve(enabled ? fileName : fileName + ModScanner.DISABLED_SUFFIX);
		Files.deleteIfExists(destination);

		try {
			Files.createLink(destination, source);
		} catch (IOException | UnsupportedOperationException e) {
			// Different volume, or a filesystem without hard links. Copying costs disk
			// but keeps the profile working, which matters more than the saving.
			Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Every hash currently held. */
	public List<String> contents() throws IOException {
		if (!Files.isDirectory(this.root)) {
			return List.of();
		}

		List<String> hashes = new ArrayList<>();
		try (Stream<Path> shards = Files.list(this.root)) {
			for (Path shard : shards.filter(Files::isDirectory).toList()) {
				try (Stream<Path> files = Files.list(shard)) {
					files.filter(Files::isRegularFile)
							.map(p -> p.getFileName().toString())
							.filter(n -> n.endsWith(".jar"))
							.forEach(n -> hashes.add(n.substring(0, n.length() - 4)));
				}
			}
		}
		return hashes;
	}

	/**
	 * Deletes stored files nothing references any more.
	 *
	 * <p>Deliberately explicit rather than automatic. The whole value of the store is
	 * that rolling back is free, and that stops being true the moment it quietly discards
	 * the version you were about to go back to.
	 *
	 * @param inUse every hash still referenced by any profile or snapshot
	 * @return bytes reclaimed
	 */
	public long prune(java.util.Set<String> inUse) throws IOException {
		long freed = 0L;
		for (String hash : contents()) {
			if (inUse.contains(hash.toLowerCase())) {
				continue;
			}
			Path file = pathFor(hash);
			freed += Files.size(file);
			Files.delete(file);
		}
		return freed;
	}
}
