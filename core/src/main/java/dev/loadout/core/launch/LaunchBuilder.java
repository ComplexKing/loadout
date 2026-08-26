package dev.loadout.core.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.loadout.core.auth.StoredAccount;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Turns version metadata plus an account into the command line that starts the game. */
public final class LaunchBuilder {
	/**
	 * Who is playing.
	 *
	 * @param accessToken the real thing for an online session, or a placeholder offline.
	 *     Whatever it is, it must never reach a log — see {@link LogRedactor}.
	 * @param online false for an offline session, which can play singleplayer but will be
	 *     rejected by any server that checks with Mojang
	 */
	public record Account(String username, String uuid, String accessToken, boolean online) {
		/**
		 * An offline session for an account that has already signed in with Microsoft.
		 *
		 * <p>There is deliberately no way to build one of these from a bare username. A
		 * launcher that starts the game for any name typed at it is a licence bypass; one
		 * that plays offline as an account which has authenticated before is doing what
		 * offline mode exists for, which is playing without a connection. Requiring a
		 * {@link StoredAccount} is what enforces that difference in code rather than in a
		 * policy document.
		 *
		 * <p>The real Minecraft UUID is reused rather than a derived one, so worlds keep
		 * the same player data whether the session was online or off.
		 */
		/**
		 * A real session, with the token the game will authenticate to Mojang with.
		 *
		 * <p>Built from a refresh that just happened, never from anything stored: a
		 * Minecraft access token lasts about a day, so one read off disk would be expired
		 * more often than not.
		 */
		public static Account online(dev.loadout.core.auth.MicrosoftAuth.Session session) {
			return new Account(session.username(), session.uuid(), session.accessToken(), true);
		}

		public static Account offlineFor(StoredAccount verified) {
			if (verified == null || !verified.isVerified()) {
				throw new IllegalArgumentException("Offline play needs an account that has signed in before");
			}
			return new Account(verified.username(), verified.uuid(), "0", false);
		}

		/** A live session, with a real token from the Microsoft sign-in chain. */
		public static Account online(String username, String uuid, String accessToken) {
			return new Account(username, uuid, accessToken, true);
		}
	}

	private LaunchBuilder() {
	}

	/**
	 * Builds the full command.
	 *
	 * @param javaExecutable the java binary to run
	 * @param gameDir the profile's own directory, which becomes the game's working folder
	 * @param extraJvmArgs anything the user configured, e.g. heap size
	 */
	public static List<String> build(
			String javaExecutable,
			JsonObject versionJson,
			JsonObject fabricProfile,
			GameInstaller installer,
			String versionId,
			Path gameDir,
			Account account,
			List<String> extraJvmArgs
	) throws java.io.IOException {
		List<LibraryResolver.Library> libraries = LibraryResolver.resolve(versionJson, fabricProfile);

		StringBuilder classpath = new StringBuilder();
		for (LibraryResolver.Library library : libraries) {
			classpath.append(installer.librariesDir().resolve(library.path())).append(File.pathSeparator);
		}
		// The client jar goes last: Fabric's loader and its dependencies must resolve
		// ahead of anything Minecraft bundles.
		classpath.append(installer.clientJar(versionId));

		Map<String, String> values = new HashMap<>();
		values.put("classpath", classpath.toString());
		values.put("natives_directory", installer.versionDir(versionId).resolve("natives").toString());
		values.put("launcher_name", "loadout");
		values.put("launcher_version", "0.1.0");
		values.put("library_directory", installer.librariesDir().toString());
		values.put("classpath_separator", File.pathSeparator);
		values.put("auth_player_name", account.username());
		values.put("auth_uuid", account.uuid().replace("-", ""));
		values.put("auth_access_token", account.accessToken());
		values.put("auth_xuid", "");
		values.put("clientid", "");
		values.put("user_type", account.online() ? "msa" : "legacy");
		values.put("version_name", versionId);
		values.put("version_type", string(versionJson, "type", "release"));
		values.put("game_directory", gameDir.toString());
		values.put("assets_root", installer.assetsDir().toString());
		values.put("assets_index_name", assetIndexId(versionJson));
		values.put("resolution_width", "854");
		values.put("resolution_height", "480");

		List<String> command = new ArrayList<>();
		command.add(javaExecutable);
		command.addAll(extraJvmArgs);

		// The version's own JVM arguments carry things like the natives path and, on
		// macOS, -XstartOnFirstThread. Skipping them produces failures that look
		// unrelated to the launcher.
		for (String argument : LibraryResolver.jvmArguments(versionJson)) {
			command.add(substitute(argument, values));
		}

		// Fabric adds its own; without these the loader starts but finds no mods.
		if (fabricProfile != null) {
			for (String argument : LibraryResolver.jvmArguments(fabricProfile)) {
				command.add(substitute(argument, values));
			}
		}

		// Windows caps a command line at roughly 32k characters, and a modern Minecraft
		// classpath is 130-odd absolute paths -- comfortably past it on any normal
		// install. Java 9+ reads arguments from an @file, which is the way out. Written
		// fresh each launch, since the classpath changes with the version.
		command.add(writeClasspathArgFile(gameDir, classpath.toString()));

		String mainClass = fabricProfile != null
				? string(fabricProfile, "mainClass", null)
				: null;
		command.add(mainClass != null ? mainClass : string(versionJson, "mainClass", "net.minecraft.client.main.Main"));

		command.addAll(gameArguments(versionJson, values));
		return command;
	}

	private static List<String> gameArguments(JsonObject versionJson, Map<String, String> values) {
		List<String> out = new ArrayList<>();
		JsonElement arguments = versionJson.get("arguments");

		if (arguments != null && arguments.isJsonObject()) {
			JsonArray game = arguments.getAsJsonObject().getAsJsonArray("game");
			if (game != null) {
				for (JsonElement element : game) {
					if (element.isJsonPrimitive()) {
						out.add(substitute(element.getAsString(), values));
						continue;
					}

					// Conditional arguments cover things like demo mode and a fixed
					// window size. None of their conditions apply to a normal launch, so
					// a plain rule check is enough.
					JsonObject conditional = element.getAsJsonObject();
					if (!LibraryResolver.allowed(conditional.getAsJsonArray("rules"))) {
						continue;
					}
					JsonElement value = conditional.get("value");
					if (value == null) {
						continue;
					}
					if (value.isJsonPrimitive()) {
						out.add(substitute(value.getAsString(), values));
					} else if (value.isJsonArray()) {
						value.getAsJsonArray().forEach(v -> out.add(substitute(v.getAsString(), values)));
					}
				}
			}
			return out;
		}

		// Versions before the arguments object used one pre-templated string.
		String legacy = string(versionJson, "minecraftArguments", null);
		if (legacy != null) {
			for (String argument : legacy.split(" ")) {
				out.add(substitute(argument, values));
			}
		}
		return out;
	}

	/**
	 * Writes the classpath to a file the JVM reads, and returns the {@code @path}
	 * argument pointing at it.
	 *
	 * <p>Escaping is the part that bites. Inside an argument file a backslash is an
	 * escape character, so a Windows path written verbatim quietly mangles itself and the
	 * classpath loses entries without anything reporting an error. Every backslash is
	 * doubled and the whole value quoted.
	 */
	static String writeClasspathArgFile(Path gameDir, String classpath) throws java.io.IOException {
		Files.createDirectories(gameDir);
		Path file = gameDir.resolve("loadout-classpath.txt");

		String escaped = classpath.replace("\\", "\\\\").replace("\"", "\\\"");
		Files.writeString(file, "-cp \"" + escaped + "\"" + System.lineSeparator(), StandardCharsets.UTF_8);

		return "@" + file;
	}

	/** Replaces {@code ${placeholder}} with its value, leaving unknown ones alone. */
	static String substitute(String template, Map<String, String> values) {
		if (template.indexOf('$') < 0) {
			return template;
		}

		String result = template;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			String token = "${" + entry.getKey() + "}";
			if (result.contains(token)) {
				result = result.replace(token, entry.getValue());
			}
		}
		return result;
	}

	private static String assetIndexId(JsonObject versionJson) {
		JsonElement assetIndex = versionJson.get("assetIndex");
		if (assetIndex != null && assetIndex.isJsonObject()) {
			JsonElement id = assetIndex.getAsJsonObject().get("id");
			if (id != null) {
				return id.getAsString();
			}
		}
		return string(versionJson, "assets", "legacy");
	}

	private static String string(JsonObject json, String key, String fallback) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
	}
}
