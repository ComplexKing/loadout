package dev.loadout.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * One published version of a mod on Modrinth.
 *
 * @param projectId the project this belongs to, stable across every version of it
 * @param versionId this specific release
 * @param versionNumber the author's own version string
 * @param gameVersions Minecraft versions it supports
 * @param loaders loaders it supports
 * @param fileName the primary file's name
 * @param downloadUrl where to fetch the primary file
 * @param sha512 the primary file's hash, for verifying a download landed intact
 * @param fileSize primary file size in bytes
 */
public record ModrinthVersion(
		String projectId,
		String versionId,
		String versionNumber,
		List<String> gameVersions,
		List<String> loaders,
		String fileName,
		String downloadUrl,
		String sha512,
		long fileSize
) {
	static ModrinthVersion from(JsonObject json) {
		// A version can carry several files -- a jar plus sources, or platform variants.
		// The one flagged primary is what a user is meant to install; falling back to the
		// first keeps older entries that predate the flag working.
		JsonObject file = primaryFile(json.getAsJsonArray("files"));

		return new ModrinthVersion(
				string(json, "project_id"),
				string(json, "id"),
				string(json, "version_number"),
				strings(json.getAsJsonArray("game_versions")),
				strings(json.getAsJsonArray("loaders")),
				file == null ? null : string(file, "filename"),
				file == null ? null : string(file, "url"),
				file == null ? null : hash(file),
				file == null ? 0L : number(file, "size")
		);
	}

	private static JsonObject primaryFile(JsonArray files) {
		if (files == null || files.isEmpty()) {
			return null;
		}

		for (JsonElement element : files) {
			JsonObject file = element.getAsJsonObject();
			JsonElement primary = file.get("primary");
			if (primary != null && primary.isJsonPrimitive() && primary.getAsBoolean()) {
				return file;
			}
		}

		return files.get(0).getAsJsonObject();
	}

	private static String hash(JsonObject file) {
		JsonElement hashes = file.get("hashes");
		return hashes != null && hashes.isJsonObject() ? string(hashes.getAsJsonObject(), "sha512") : null;
	}

	private static String string(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}

	private static long number(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsLong() : 0L;
	}

	private static List<String> strings(JsonArray array) {
		if (array == null) {
			return List.of();
		}
		List<String> out = new ArrayList<>(array.size());
		array.forEach(e -> out.add(e.getAsString()));
		return List.copyOf(out);
	}

	/**
	 * The same version with the hash the file actually turned out to have.
	 *
	 * <p>Normally identical to what Modrinth advertised. It matters for the rare version
	 * that lists no hash at all, where the store's computed one is the only identity the
	 * file has.
	 */
	public ModrinthVersion withResolvedHash(String actualSha512) {
		return new ModrinthVersion(this.projectId, this.versionId, this.versionNumber,
				this.gameVersions, this.loaders, this.fileName, this.downloadUrl,
				actualSha512, this.fileSize);
	}

	public String projectUrl() {
		return "https://modrinth.com/project/" + this.projectId;
	}
}
