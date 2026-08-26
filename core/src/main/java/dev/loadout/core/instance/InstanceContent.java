package dev.loadout.core.instance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Everything in an instance folder that is not a mod.
 *
 * <p>Worlds, servers, resource packs, shader packs and logs all live as plain files the
 * game wrote, and a launcher that manages mods but cannot tell you what worlds it holds
 * is only managing half the instance. None of this is derived from Loadout's own records
 * -- it is read from disk each time, so a world created by the game a minute ago is
 * simply there.
 */
public final class InstanceContent {
	private final Path root;

	public InstanceContent(Path instanceDir) {
		this.root = instanceDir;
	}

	/**
	 * @param folder the directory name, which is what the game uses as an identifier
	 * @param name the display name from level.dat, which is frequently different
	 * @param lastPlayed epoch millis, or 0 when unknown
	 * @param sizeBytes total on disk, since worlds are usually the largest thing here
	 */
	public record World(String folder, String name, long lastPlayed, long sizeBytes) {
	}

	/** @param address host and port as typed, which is what identifies a server entry */
	public record Server(String name, String address) {
	}

	/** @param name file name; @param sizeBytes size; @param modifiedAt epoch millis */
	public record Pack(String name, long sizeBytes, long modifiedAt, boolean enabled) {
	}

	/** @param name file name, newest first; latest.log is the current session */
	public record LogFile(String name, long sizeBytes, long modifiedAt) {
	}

	// -- worlds ------------------------------------------------------------------------

	public List<World> worlds() throws IOException {
		Path saves = this.root.resolve("saves");
		if (!Files.isDirectory(saves)) {
			return List.of();
		}

		List<World> found = new ArrayList<>();
		try (Stream<Path> entries = Files.list(saves)) {
			for (Path dir : entries.filter(Files::isDirectory).toList()) {
				Path level = dir.resolve("level.dat");

				String name = dir.getFileName().toString();
				long lastPlayed = 0L;

				if (Files.isRegularFile(level)) {
					Map<String, Object> nbt = Nbt.read(level);
					String stored = Nbt.string(nbt, "Data", "LevelName");
					if (stored != null && !stored.isBlank()) {
						name = stored;
					}
					Object played = Nbt.path(nbt, "Data", "LastPlayed");
					if (played instanceof Long millis) {
						lastPlayed = millis;
					}
				}

				found.add(new World(dir.getFileName().toString(), name, lastPlayed, sizeOf(dir)));
			}
		}

		// Most recently played first, which is the order anyone looking for a world wants.
		found.sort(Comparator.comparingLong(World::lastPlayed).reversed());
		return List.copyOf(found);
	}

	// -- servers -----------------------------------------------------------------------

	/**
	 * The multiplayer list, in the order the game shows it.
	 *
	 * <p>Not sorted: the order in servers.dat is the order someone dragged them into, and
	 * rearranging it here would make this list disagree with the one in game.
	 */
	@SuppressWarnings("unchecked")
	public List<Server> servers() throws IOException {
		Path file = this.root.resolve("servers.dat");
		if (!Files.isRegularFile(file)) {
			return List.of();
		}

		Object list = Nbt.path(Nbt.read(file), "servers");
		if (!(list instanceof List<?> entries)) {
			return List.of();
		}

		List<Server> found = new ArrayList<>();
		for (Object entry : entries) {
			if (entry instanceof Map<?, ?> server) {
				Object name = server.get("name");
				Object ip = server.get("ip");
				if (ip instanceof String address) {
					found.add(new Server(name instanceof String text ? text : address, address));
				}
			}
		}
		return List.copyOf(found);
	}

	// -- packs -------------------------------------------------------------------------

	public List<Pack> resourcePacks() throws IOException {
		return packsIn("resourcepacks");
	}

	public List<Pack> shaderPacks() throws IOException {
		// Iris and OptiFine both use this folder, so it is the right one whichever is used.
		return packsIn("shaderpacks");
	}

	private List<Pack> packsIn(String folder) throws IOException {
		Path dir = this.root.resolve(folder);
		if (!Files.isDirectory(dir)) {
			return List.of();
		}

		List<Pack> found = new ArrayList<>();
		try (Stream<Path> entries = Files.list(dir)) {
			for (Path path : entries.toList()) {
				String name = path.getFileName().toString();
				// A pack can be a zip or an unpacked folder, and both are valid to the game.
				if (!Files.isDirectory(path) && !name.endsWith(".zip") && !name.endsWith(".zip.disabled")) {
					continue;
				}

				boolean enabled = !name.endsWith(".disabled");
				found.add(new Pack(
						enabled ? name : name.substring(0, name.length() - ".disabled".length()),
						Files.isDirectory(path) ? sizeOf(path) : Files.size(path),
						Files.getLastModifiedTime(path).toMillis(),
						enabled));
			}
		}

		found.sort(Comparator.comparing(Pack::name, String.CASE_INSENSITIVE_ORDER));
		return List.copyOf(found);
	}

	// -- screenshots -------------------------------------------------------------------

	/**
	 * @param name file name
	 * @param path absolute path, so the interface can show the image itself
	 * @param sizeBytes size on disk
	 * @param takenAt epoch millis
	 */
	public record Screenshot(String name, String path, long sizeBytes, long takenAt) {
	}

	/**
	 * Screenshots the game has taken, newest first.
	 *
	 * <p>Worth surfacing because they are the one thing in an instance folder people
	 * actually want to look at, and the game gives no way to browse them without leaving
	 * it. Newest first because the reason to open this is almost always the last one.
	 */
	public List<Screenshot> screenshots() throws IOException {
		Path dir = this.root.resolve("screenshots");
		if (!Files.isDirectory(dir)) {
			return List.of();
		}

		List<Screenshot> found = new ArrayList<>();
		try (Stream<Path> entries = Files.list(dir)) {
			for (Path file : entries.filter(Files::isRegularFile).toList()) {
				String name = file.getFileName().toString();
				String lower = name.toLowerCase();
				if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
					continue;
				}
				found.add(new Screenshot(name, file.toAbsolutePath().toString(),
						Files.size(file), Files.getLastModifiedTime(file).toMillis()));
			}
		}

		found.sort(Comparator.comparingLong(Screenshot::takenAt).reversed());
		return List.copyOf(found);
	}

	// -- logs --------------------------------------------------------------------------

	public List<LogFile> logs() throws IOException {
		List<LogFile> found = new ArrayList<>();

		// The game writes to logs/, and Loadout writes its own launch log beside it.
		for (Path dir : List.of(this.root.resolve("logs"), this.root)) {
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (Stream<Path> entries = Files.list(dir)) {
				for (Path file : entries.filter(Files::isRegularFile).toList()) {
					String name = file.getFileName().toString();
					if (!name.endsWith(".log") && !name.endsWith(".log.gz") && !name.endsWith(".txt")) {
						continue;
					}
					found.add(new LogFile(
							dir.equals(this.root) ? name : "logs/" + name,
							Files.size(file),
							Files.getLastModifiedTime(file).toMillis()));
				}
			}
		}

		found.sort(Comparator.comparingLong(LogFile::modifiedAt).reversed());
		return List.copyOf(found);
	}

	/**
	 * Reads the tail of a log.
	 *
	 * <p>The tail rather than the whole file, because a crash log can be tens of megabytes
	 * and the interesting part is always at the end.
	 *
	 * @param maxBytes how much of the end to return
	 */
	public String logTail(String name, int maxBytes) throws IOException {
		Path file = resolveInside(name);
		if (file == null || !Files.isRegularFile(file)) {
			throw new IOException("No such log: " + name);
		}

		long size = Files.size(file);
		try (var channel = java.nio.channels.FileChannel.open(file)) {
			long from = Math.max(0, size - maxBytes);
			java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate((int) Math.min(size, maxBytes));
			channel.position(from);
			channel.read(buffer);

			String text = new String(buffer.array(), 0, buffer.position(), java.nio.charset.StandardCharsets.UTF_8);
			// A partial first line is noise; drop it when the file was truncated.
			int newline = from > 0 ? text.indexOf('\n') : -1;
			return newline >= 0 ? text.substring(newline + 1) : text;
		}
	}

	/**
	 * Resolves a name from a client against this instance, refusing anything that escapes.
	 *
	 * <p>The name arrives over HTTP, so "logs/../../../../etc/passwd" is a thing that can
	 * be asked for. Comparing normalised absolute paths is what makes the answer no.
	 */
	private Path resolveInside(String name) {
		Path resolved = this.root.resolve(name).normalize().toAbsolutePath();
		return resolved.startsWith(this.root.normalize().toAbsolutePath()) ? resolved : null;
	}

	private static long sizeOf(Path dir) throws IOException {
		try (Stream<Path> walk = Files.walk(dir)) {
			return walk.filter(Files::isRegularFile).mapToLong(path -> {
				try {
					return Files.size(path);
				} catch (IOException e) {
					return 0L;
				}
			}).sum();
		}
	}
}
