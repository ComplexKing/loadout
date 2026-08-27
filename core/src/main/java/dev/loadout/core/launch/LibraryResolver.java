package dev.loadout.core.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Works out which libraries this machine needs, and where each one lives.
 *
 * <p>Two formats have to be understood. Mojang ships each library with an explicit
 * download block and a set of rules; Fabric ships a Maven coordinate plus a repository
 * and expects the launcher to derive the path. Both end up as the same thing here.
 */
public final class LibraryResolver {
	/** What Mojang calls this operating system in its rules. */
	public static final String OS_NAME = detectOs();

	/**
	 * @param path repository-relative path, also used under the local libraries folder
	 * @param url where to download it, or null if it isn't downloadable
	 * @param size expected bytes, 0 when unknown
	 */
	public record Library(String path, String url, long size) {
	}

	private LibraryResolver() {
	}

	private static String detectOs() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			return "windows";
		}
		if (os.contains("mac") || os.contains("darwin")) {
			return "osx";
		}
		return "linux";
	}

	/**
	 * Every library needed to launch, in classpath order.
	 *
	 * <p>Fabric's libraries come first. Fabric's loader has to be ahead of Minecraft on
	 * the classpath for it to do its job, and its own dependencies have to be ahead of
	 * anything Minecraft ships that might collide with them.
	 *
	 * <p>Duplicates are collapsed by Maven coordinate, keeping the first seen. Fabric and
	 * Minecraft genuinely do ship overlapping libraries at different versions, and letting
	 * both onto the classpath produces failures that look like anything but a duplicate.
	 */
	public static List<Library> resolve(JsonObject versionJson, JsonObject fabricProfile) {
		Map<String, Library> byCoordinate = new LinkedHashMap<>();

		if (fabricProfile != null) {
			collect(fabricProfile.getAsJsonArray("libraries"), byCoordinate);
		}
		collect(versionJson.getAsJsonArray("libraries"), byCoordinate);

		return List.copyOf(byCoordinate.values());
	}

	private static void collect(JsonArray libraries, Map<String, Library> into) {
		if (libraries == null) {
			return;
		}

		for (JsonElement element : libraries) {
			JsonObject library = element.getAsJsonObject();
			if (!allowed(library.getAsJsonArray("rules"), java.util.Set.of())) {
				continue;
			}

			String name = string(library, "name");
			if (name == null) {
				continue;
			}

			// Collapse on group:artifact:classifier, ignoring version, so two versions of
			// the same library can't both reach the classpath.
			String key = coordinateKey(name);
			if (into.containsKey(key)) {
				continue;
			}

			Library resolved = fromMojang(library);
			if (resolved == null) {
				resolved = fromMaven(library, name);
			}
			if (resolved != null) {
				into.put(key, resolved);
			}
		}
	}

	/** Mojang's format: an explicit artifact block with path, url and size. */
	private static Library fromMojang(JsonObject library) {
		JsonElement downloads = library.get("downloads");
		if (downloads == null || !downloads.isJsonObject()) {
			return null;
		}

		JsonElement artifact = downloads.getAsJsonObject().get("artifact");
		if (artifact == null || !artifact.isJsonObject()) {
			return null;
		}

		JsonObject object = artifact.getAsJsonObject();
		String path = string(object, "path");
		return path == null ? null : new Library(path, string(object, "url"), number(object, "size"));
	}

	/** Fabric's format: a coordinate plus a repository to build the path from. */
	private static Library fromMaven(JsonObject library, String name) {
		String path = mavenPath(name);
		if (path == null) {
			return null;
		}

		String repository = string(library, "url");
		if (repository == null) {
			repository = "https://maven.fabricmc.net/";
		}
		if (!repository.endsWith("/")) {
			repository = repository + "/";
		}

		return new Library(path, repository + path, 0L);
	}

	/** {@code group:artifact:version[:classifier]} to a Maven repository path. */
	static String mavenPath(String coordinate) {
		String[] parts = coordinate.split(":");
		if (parts.length < 3) {
			return null;
		}

		String group = parts[0].replace('.', '/');
		String artifact = parts[1];
		String version = parts[2];
		String classifier = parts.length > 3 ? "-" + parts[3] : "";

		return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
	}

	/**
	 * Identity for deduplication: group, artifact and classifier, but not version.
	 *
	 * <p>The classifier has to be part of this. Every LWJGL module appears seven times in
	 * a modern version manifest -- the classes jar plus six {@code natives-*} variants --
	 * and they all share a group and artifact. Keying on those alone collapses them to
	 * one entry, and if a natives jar happens to come first the real classes are dropped.
	 * The result is a launch that gets all the way into Fabric before dying on
	 * NoClassDefFoundError for org/lwjgl/Version, which points nowhere near the cause.
	 *
	 * <p>Version is still excluded, because Fabric and Minecraft genuinely do ship the
	 * same library at different versions and only one of those may be loaded.
	 */
	static String coordinateKey(String coordinate) {
		String[] parts = coordinate.split(":");
		if (parts.length < 2) {
			return coordinate;
		}

		String classifier = parts.length > 3 ? parts[3] : "";
		return parts[0] + ":" + parts[1] + ":" + classifier;
	}

	/**
	 * Evaluates Mojang's rule list for this machine.
	 *
	 * <p>Rules are applied in order and the last matching one wins, which is how Mojang
	 * expresses "everywhere except macOS" as allow-then-disallow. No rules at all means
	 * allowed; rules present with none matching means denied.
	 */
	/** No features enabled, which is every caller that does not care about quick play. */
	static boolean allowed(JsonArray rules) {
		return allowed(rules, java.util.Set.of());
	}

	static boolean allowed(JsonArray rules, java.util.Set<String> enabledFeatures) {
		if (rules == null || rules.isEmpty()) {
			return true;
		}

		boolean permitted = false;
		for (JsonElement element : rules) {
			JsonObject rule = element.getAsJsonObject();
			if (!ruleMatches(rule, enabledFeatures)) {
				continue;
			}
			permitted = "allow".equals(string(rule, "action"));
		}
		return permitted;
	}

	private static boolean ruleMatches(JsonObject rule, java.util.Set<String> enabledFeatures) {
		// Feature conditions gate optional launch modes: demo, a fixed window size, and
		// the quick-play variants that jump straight into a world or server. Loadout
		// requests none of them, so any rule depending on a feature does not apply.
		//
		// Ignoring this is not harmless. Every quick-play argument is behind its own
		// feature rule, so treating them all as unconditional puts three mutually
		// exclusive options on one command line, and Minecraft refuses to start with
		// "Only one quick play option can be specified" -- after Fabric and every mod
		// have already loaded, which makes it look like a mod problem.
		JsonElement features = rule.get("features");
		if (features != null && features.isJsonObject() && !features.getAsJsonObject().isEmpty()) {
			// A rule guarded by features applies only when every one it names is switched
			// on. Refusing all of them was the earlier fix for emitting every quick-play
			// argument at once; refusing them selectively is what lets one be used.
			for (var entry : features.getAsJsonObject().entrySet()) {
				boolean wanted = entry.getValue().isJsonPrimitive()
						&& entry.getValue().getAsBoolean();
				if (wanted != enabledFeatures.contains(entry.getKey())) {
					return false;
				}
			}
		}

		JsonElement os = rule.get("os");
		if (os == null || !os.isJsonObject()) {
			return true;  // no condition, so it applies everywhere
		}

		String required = string(os.getAsJsonObject(), "name");
		if (required != null && !required.equals(OS_NAME)) {
			return false;
		}

		String arch = string(os.getAsJsonObject(), "arch");
		if (arch != null) {
			String actual = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
			// Mojang writes "x86" for 32-bit; 64-bit machines report amd64 or x86_64.
			boolean is32 = actual.equals("x86") || actual.equals("i386");
			if (arch.equals("x86") != is32) {
				return false;
			}
		}

		return true;
	}

	/** Extra JVM arguments a version asks for, filtered by the same rules. */
	public static List<String> jvmArguments(JsonObject versionJson) {
		List<String> out = new ArrayList<>();
		JsonElement arguments = versionJson.get("arguments");
		if (arguments == null || !arguments.isJsonObject()) {
			return out;
		}

		JsonArray jvm = arguments.getAsJsonObject().getAsJsonArray("jvm");
		if (jvm == null) {
			return out;
		}

		for (JsonElement element : jvm) {
			if (element.isJsonPrimitive()) {
				out.add(element.getAsString());
				continue;
			}

			JsonObject conditional = element.getAsJsonObject();
			if (!allowed(conditional.getAsJsonArray("rules"))) {
				continue;
			}

			JsonElement value = conditional.get("value");
			if (value == null) {
				continue;
			}
			if (value.isJsonPrimitive()) {
				out.add(value.getAsString());
			} else if (value.isJsonArray()) {
				value.getAsJsonArray().forEach(v -> out.add(v.getAsString()));
			}
		}

		return out;
	}

	private static String string(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}

	private static long number(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsLong() : 0L;
	}
}
