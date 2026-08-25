package dev.loadout.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads a mods folder into {@link ModJar} records. */
public final class ModScanner {
	/**
	 * How every launcher marks a mod as off. Renaming rather than deleting means the
	 * file survives being toggled, which is what lets a disable be undone offline.
	 */
	public static final String DISABLED_SUFFIX = ".disabled";

	private static final int HASH_BUFFER = 1 << 16;

	private ModScanner() {
	}

	/**
	 * Scans a mods directory. Sub-directories are ignored: Fabric doesn't load nested
	 * mods, so anything in one is a user's own filing rather than something active.
	 *
	 * @return every jar found, sorted by display name, enabled and disabled alike
	 */
	public static List<ModJar> scan(Path modsDir) throws IOException {
		if (!Files.isDirectory(modsDir)) {
			return List.of();
		}

		List<ModJar> found = new ArrayList<>();
		try (Stream<Path> entries = Files.list(modsDir)) {
			entries.filter(Files::isRegularFile)
					.filter(ModScanner::looksLikeMod)
					.forEach(path -> {
						try {
							found.add(read(path));
						} catch (IOException e) {
							throw new UncheckedIOException(e);
						}
					});
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}

		found.sort(Comparator.comparing(ModJar::displayName, String.CASE_INSENSITIVE_ORDER));
		return found;
	}

	private static boolean looksLikeMod(Path path) {
		String name = path.getFileName().toString().toLowerCase();
		return name.endsWith(".jar") || name.endsWith(".jar" + DISABLED_SUFFIX);
	}

	/** Hashes a jar and pulls what metadata it declares about itself. */
	public static ModJar read(Path path) throws IOException {
		String hash = sha512(path);
		long size = Files.size(path);
		boolean enabled = !path.getFileName().toString().endsWith(DISABLED_SUFFIX);

		String modId = null;
		String name = null;
		String version = null;
		List<String> minecraftVersions = List.of();

		// A jar with no fabric.mod.json isn't an error -- it's a plain library, or a mod
		// for a different loader. Record what we can and let the caller decide.
		try (ZipFile zip = new ZipFile(path.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry != null) {
				try (InputStream in = zip.getInputStream(entry);
						InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
					JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
					modId = string(json, "id");
					name = string(json, "name");
					version = string(json, "version");
					minecraftVersions = minecraftDependency(json);
				}
			}
		} catch (RuntimeException e) {
			// Malformed metadata shouldn't lose the file. The hash is what actually
			// identifies it, and that we already have.
			modId = null;
		}

		return new ModJar(path, hash, size, enabled, modId, name, version, minecraftVersions);
	}

	private static String string(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}

	/**
	 * The Minecraft versions a mod says it needs.
	 *
	 * <p>These are ranges like {@code ~26.2} or {@code >=1.21 <1.22}, not plain versions,
	 * so they're kept as declared rather than parsed. They're a hint for display; the
	 * authoritative answer about what a mod supports comes from Modrinth.
	 */
	private static List<String> minecraftDependency(JsonObject json) {
		JsonElement depends = json.get("depends");
		if (depends == null || !depends.isJsonObject()) {
			return List.of();
		}

		JsonElement minecraft = depends.getAsJsonObject().get("minecraft");
		if (minecraft == null) {
			return List.of();
		}
		if (minecraft.isJsonPrimitive()) {
			return List.of(minecraft.getAsString());
		}
		if (minecraft.isJsonArray()) {
			List<String> out = new ArrayList<>();
			minecraft.getAsJsonArray().forEach(e -> out.add(e.getAsString()));
			return List.copyOf(out);
		}
		return List.of();
	}

	/** Streams the file rather than loading it, since mod jars run to tens of megabytes. */
	public static String sha512(Path path) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-512");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-512 is required by every Java platform", e);
		}

		try (InputStream in = Files.newInputStream(path)) {
			byte[] buffer = new byte[HASH_BUFFER];
			int read;
			while ((read = in.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
		}

		StringBuilder hex = new StringBuilder(128);
		for (byte b : digest.digest()) {
			hex.append(Character.forDigit((b >> 4) & 0xF, 16));
			hex.append(Character.forDigit(b & 0xF, 16));
		}
		return hex.toString();
	}
}
