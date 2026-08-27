package dev.loadout.core.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds Java installations and picks one that can run a given Minecraft version.
 *
 * <p>This is the single most common reason a modded install won't start. Minecraft 26.1+
 * needs Java 25, 1.20.5 through 1.21.x need 21, and older versions need 17 or 8 — and the
 * error you get from the wrong one is an unhelpful class-version number. Choosing
 * correctly on the user's behalf removes a whole category of "it just doesn't work".
 */
public final class JavaLocator {
	/**
	 * @param executable path to the java binary
	 * @param majorVersion 21, 25, and so on
	 * @param source where it was found, for showing the user
	 */
	public record JavaInstall(Path executable, int majorVersion, String source) {
	}

	private JavaLocator() {
	}

	/**
	 * Everything we can find, newest first, probing every candidate afresh.
	 *
	 * <p>Slow, and unavoidably so: the only reliable way to learn a JVM's version is to run
	 * it. Prefer {@link #findAll(Path)}, which remembers the answers.
	 */
	public static List<JavaInstall> findAll() {
		return findAll(null);
	}

	/**
	 * Everything we can find, newest first, remembering what each binary turned out to be.
	 *
	 * <h2>Why the cache matters</h2>
	 *
	 * <p>Identifying a JVM means starting it: {@code java -version} as a subprocess, once
	 * per candidate, waiting for each. On an ordinary developer machine with four or five
	 * JDKs installed that measured at 4.5 seconds -- and it ran on every single launch,
	 * making it most of the delay between pressing Play and Minecraft starting.
	 *
	 * <p>A binary that has not changed has not changed version, so the answer is recorded
	 * against the file's size and modification time. Replace a JDK in place and it gets
	 * probed again; leave it alone and it never does.
	 *
	 * @param cacheFile where to keep what was learned, or null to probe everything
	 */
	public static List<JavaInstall> findAll(Path cacheFile) {
		Set<Path> candidates = new LinkedHashSet<>();

		// The JVM running Loadout is always a candidate, and on a fresh machine it may be
		// the only one.
		Path current = Path.of(System.getProperty("java.home"), "bin", executableName());
		if (Files.isRegularFile(current)) {
			candidates.add(current);
		}

		String javaHome = System.getenv("JAVA_HOME");
		if (javaHome != null && !javaHome.isBlank()) {
			Path fromEnv = Path.of(javaHome, "bin", executableName());
			if (Files.isRegularFile(fromEnv)) {
				candidates.add(fromEnv);
			}
		}

		for (Path root : searchRoots()) {
			candidates.addAll(scanForJavaHomes(root));
		}

		Map<String, Remembered> cache = readCache(cacheFile);
		Map<String, Remembered> updated = new LinkedHashMap<>();

		List<JavaInstall> installs = new ArrayList<>();
		for (Path candidate : candidates) {
			String key = candidate.toString();
			Stamp stamp = stampOf(candidate);
			Remembered known = cache.get(key);

			// A hit means not starting a JVM to be told what we already wrote down.
			if (known != null && stamp != null && known.matches(stamp)) {
				updated.put(key, known);
				if (known.major() > 0) {
					installs.add(new JavaInstall(candidate, known.major(), key));
				}
				continue;
			}

			Optional<JavaInstall> probed = probe(candidate);
			probed.ifPresent(installs::add);

			if (stamp != null) {
				// Failures are remembered too, as a major of zero. Something on the path
				// that is not a usable JVM would otherwise be re-probed on every launch,
				// which is the slow case rather than the rare one.
				updated.put(key, new Remembered(stamp.lastModified(), stamp.size(),
						probed.map(JavaInstall::majorVersion).orElse(0)));
			}
		}

		writeCache(cacheFile, updated);

		installs.sort((a, b) -> Integer.compare(b.majorVersion(), a.majorVersion()));
		return installs;
	}

	// -- remembering what each binary is ------------------------------------------------

	/** @param major the version it reported, or 0 for "probed, and not a usable JVM" */
	private record Remembered(long lastModified, long size, int major) {
		boolean matches(Stamp stamp) {
			return this.lastModified == stamp.lastModified() && this.size == stamp.size();
		}
	}

	private record Stamp(long lastModified, long size) {
	}

	private static Stamp stampOf(Path executable) {
		try {
			return new Stamp(Files.getLastModifiedTime(executable).toMillis(), Files.size(executable));
		} catch (IOException e) {
			return null;   // vanished between listing and stat; treat as unknown
		}
	}

	private static Map<String, Remembered> readCache(Path cacheFile) {
		if (cacheFile == null || !Files.isRegularFile(cacheFile)) {
			return Map.of();
		}

		try {
			com.google.gson.JsonObject root = com.google.gson.JsonParser
					.parseString(Files.readString(cacheFile, java.nio.charset.StandardCharsets.UTF_8))
					.getAsJsonObject();

			Map<String, Remembered> found = new LinkedHashMap<>();
			for (var entry : root.entrySet()) {
				com.google.gson.JsonObject value = entry.getValue().getAsJsonObject();
				found.put(entry.getKey(), new Remembered(
						value.get("lastModified").getAsLong(),
						value.get("size").getAsLong(),
						value.get("major").getAsInt()));
			}
			return found;
		} catch (IOException | RuntimeException e) {
			// A cache is a convenience. Anything unreadable is worth forgetting rather
			// than worth failing a launch over.
			return Map.of();
		}
	}

	private static void writeCache(Path cacheFile, Map<String, Remembered> entries) {
		if (cacheFile == null) {
			return;
		}

		try {
			com.google.gson.JsonObject root = new com.google.gson.JsonObject();
			entries.forEach((key, value) -> {
				com.google.gson.JsonObject json = new com.google.gson.JsonObject();
				json.addProperty("lastModified", value.lastModified());
				json.addProperty("size", value.size());
				json.addProperty("major", value.major());
				root.add(key, json);
			});

			Files.createDirectories(cacheFile.getParent());
			Files.writeString(cacheFile, root.toString(), java.nio.charset.StandardCharsets.UTF_8);
		} catch (IOException e) {
			// Not being able to write it costs speed, not correctness.
		}
	}

	/**
	 * Picks a Java that satisfies a version's requirement.
	 *
	 * <p>Prefers an exact match. Minecraft's declared requirement is a floor rather than a
	 * pin, but running far ahead of what a version was built against is a good way to meet
	 * a removed internal API, so the closest suitable one wins.
	 */
	public static Optional<JavaInstall> bestFor(int requiredMajor) {
		return bestFor(requiredMajor, null);
	}

	public static Optional<JavaInstall> bestFor(int requiredMajor, Path cacheFile) {
		List<JavaInstall> installs = findAll(cacheFile);

		return installs.stream()
				.filter(install -> install.majorVersion() == requiredMajor)
				.findFirst()
				.or(() -> installs.stream()
						.filter(install -> install.majorVersion() > requiredMajor)
						.min((a, b) -> Integer.compare(a.majorVersion(), b.majorVersion())));
	}

	/** The Java version a Minecraft version asks for, defaulting to 8 for old releases. */
	public static int requiredMajor(com.google.gson.JsonObject versionJson) {
		var element = versionJson.get("javaVersion");
		if (element != null && element.isJsonObject()) {
			var major = element.getAsJsonObject().get("majorVersion");
			if (major != null) {
				return major.getAsInt();
			}
		}
		return 8;
	}

	private static String executableName() {
		return System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
	}

	private static List<Path> searchRoots() {
		List<Path> roots = new ArrayList<>();
		String home = System.getProperty("user.home");

		// Where the common vendors and tools put JDKs. Gradle's is worth including
		// because a machine that has built a mod usually has a suitable JDK there
		// already, even when nothing is installed system-wide.
		roots.add(Path.of(home, ".gradle", "jdks"));
		roots.add(Path.of(home, ".jdks"));
		roots.add(Path.of(home, ".sdkman", "candidates", "java"));

		if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
			roots.add(Path.of("C:", "Program Files", "Java"));
			roots.add(Path.of("C:", "Program Files", "Eclipse Adoptium"));
			roots.add(Path.of("C:", "Program Files", "Microsoft"));
			roots.add(Path.of("C:", "Program Files", "Zulu"));
		} else {
			roots.add(Path.of("/usr/lib/jvm"));
			roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
		}

		return roots;
	}

	private static List<Path> scanForJavaHomes(Path root) {
		List<Path> found = new ArrayList<>();
		if (!Files.isDirectory(root)) {
			return found;
		}

		try (Stream<Path> entries = Files.list(root)) {
			for (Path entry : entries.filter(Files::isDirectory).toList()) {
				// Straightforward layout, plus the macOS bundle layout.
				for (Path relative : List.of(
						Path.of("bin", executableName()),
						Path.of("Contents", "Home", "bin", executableName()))) {
					Path executable = entry.resolve(relative);
					if (Files.isRegularFile(executable)) {
						found.add(executable);
						break;
					}
				}
			}
		} catch (IOException e) {
			// An unreadable directory just means no candidates from it.
		}

		return found;
	}

	/**
	 * Asks a java binary what version it is.
	 *
	 * <p>Running it is the only reliable answer: directory names lie, and vendors disagree
	 * about how to spell a version in a path.
	 */
	private static Optional<JavaInstall> probe(Path executable) {
		try {
			Process process = new ProcessBuilder(executable.toString(), "-version")
					.redirectErrorStream(true)
					.start();

			String output;
			try (var stream = process.getInputStream()) {
				output = new String(stream.readAllBytes());
			}
			process.waitFor();

			Integer major = parseMajor(output);
			return major == null
					? Optional.empty()
					: Optional.of(new JavaInstall(executable, major, executable.toString()));
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return Optional.empty();
		}
	}

	/** Handles both the legacy {@code 1.8.0_x} spelling and the modern {@code 21.0.1}. */
	static Integer parseMajor(String versionOutput) {
		var matcher = java.util.regex.Pattern
				.compile("version \"(\\d+)(?:\\.(\\d+))?")
				.matcher(versionOutput);
		if (!matcher.find()) {
			return null;
		}

		int first = Integer.parseInt(matcher.group(1));
		if (first == 1 && matcher.group(2) != null) {
			return Integer.parseInt(matcher.group(2));
		}
		return first;
	}
}
