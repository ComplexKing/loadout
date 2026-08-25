package dev.loadout.core.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

	/** Everything we can find, newest first. */
	public static List<JavaInstall> findAll() {
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

		List<JavaInstall> installs = new ArrayList<>();
		for (Path candidate : candidates) {
			probe(candidate).ifPresent(installs::add);
		}

		installs.sort((a, b) -> Integer.compare(b.majorVersion(), a.majorVersion()));
		return installs;
	}

	/**
	 * Picks a Java that satisfies a version's requirement.
	 *
	 * <p>Prefers an exact match. Minecraft's declared requirement is a floor rather than a
	 * pin, but running far ahead of what a version was built against is a good way to meet
	 * a removed internal API, so the closest suitable one wins.
	 */
	public static Optional<JavaInstall> bestFor(int requiredMajor) {
		List<JavaInstall> installs = findAll();

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
