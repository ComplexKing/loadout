package dev.loadout.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Loadout's own settings, including credentials for sources that need them.
 *
 * <p>Kept separate from profiles because it is machine-scoped rather than
 * instance-scoped, and because it holds a secret: a CurseForge API key is tied to an
 * account and should not be copied around with a profile someone shares.
 */
public final class Settings {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private String curseForgeApiKey;

	/** May be null; CurseForge is simply unavailable until one is set. */
	public String curseForgeApiKey() {
		return this.curseForgeApiKey;
	}

	public void setCurseForgeApiKey(String key) {
		this.curseForgeApiKey = key == null || key.isBlank() ? null : key.trim();
	}

	public static Path fileIn(Path loadoutRoot) {
		return loadoutRoot.resolve("settings.json");
	}

	public static Settings load(Path loadoutRoot) {
		Path file = fileIn(loadoutRoot);
		if (!Files.isRegularFile(file)) {
			return new Settings();
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Settings loaded = GSON.fromJson(reader, Settings.class);
			return loaded == null ? new Settings() : loaded;
		} catch (Exception e) {
			// A malformed settings file should not stop the launcher starting; defaults
			// mean CurseForge is off, which is a working state.
			return new Settings();
		}
	}

	public void save(Path loadoutRoot) throws IOException {
		Path file = fileIn(loadoutRoot);
		Files.createDirectories(file.getParent());

		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(this, writer);
		}

		// Owner-only where the platform supports it. This holds an API key.
		try {
			Files.setPosixFilePermissions(file,
					Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		} catch (IOException | UnsupportedOperationException e) {
			// Windows has no POSIX permissions; files under the user profile are already
			// scoped to that user by its ACL.
		}
	}
}
