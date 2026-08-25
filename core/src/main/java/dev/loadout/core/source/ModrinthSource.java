package dev.loadout.core.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Modrinth. Open API, no credentials, SHA-512 file hashes. */
public final class ModrinthSource implements ModSource {
	private static final String API = "https://api.modrinth.com/v2";

	private final Http http = new Http();

	@Override
	public SourceId id() {
		return SourceId.MODRINTH;
	}

	@Override
	public boolean isAvailable() {
		return true;  // no key required
	}

	@Override
	public String unavailableReason() {
		return null;
	}

	@Override
	public List<RemoteMod> search(String query, String gameVersion, String loader,
			SortOrder sort, int limit, int offset) throws IOException, InterruptedException {
		String facets = "[[\"project_type:mod\"]"
				+ ",[\"versions:" + gameVersion + "\"]"
				+ ",[\"categories:" + loader + "\"]]";

		StringBuilder url = new StringBuilder(API + "/search?limit=" + limit + "&offset=" + offset)
				.append("&index=").append(Http.encode(indexFor(sort)))
				.append("&facets=").append(Http.encode(facets));
		if (query != null && !query.isBlank()) {
			url.append("&query=").append(Http.encode(query));
		}

		JsonArray hits = this.http.getObject(url.toString()).getAsJsonArray("hits");
		List<RemoteMod> results = new ArrayList<>(hits.size());
		for (JsonElement element : hits) {
			JsonObject hit = element.getAsJsonObject();
			results.add(new RemoteMod(
					SourceId.MODRINTH,
					Http.string(hit, "project_id"),
					Http.string(hit, "slug"),
					Http.string(hit, "title"),
					Http.string(hit, "description"),
					Http.string(hit, "author"),
					Http.number(hit, "downloads"),
					Http.string(hit, "icon_url"),
					strings(hit.getAsJsonArray("display_categories"))));
		}
		return results;
	}

	private static String indexFor(SortOrder sort) {
		return switch (sort) {
			case DOWNLOADS -> "downloads";
			case UPDATED -> "updated";
			case NEWEST -> "newest";
			default -> "relevance";
		};
	}

	@Override
	public Optional<RemoteFile> bestFile(String modId, String gameVersion, String loader)
			throws IOException, InterruptedException {
		String url = API + "/project/" + Http.encode(modId) + "/version"
				+ "?loaders=" + Http.encode("[\"" + loader + "\"]")
				+ "&game_versions=" + Http.encode("[\"" + gameVersion + "\"]");

		JsonArray versions;
		try {
			versions = JsonParser.parseString(this.http.get(url)).getAsJsonArray();
		} catch (Http.NotFound e) {
			return Optional.empty();
		}
		if (versions.isEmpty()) {
			return Optional.empty();
		}

		// Modrinth returns newest first.
		return Optional.of(toFile(versions.get(0).getAsJsonObject()));
	}

	private static RemoteFile toFile(JsonObject version) {
		JsonObject file = primaryFile(version.getAsJsonArray("files"));

		List<String> required = new ArrayList<>();
		JsonArray dependencies = version.getAsJsonArray("dependencies");
		if (dependencies != null) {
			for (JsonElement element : dependencies) {
				JsonObject dependency = element.getAsJsonObject();
				// Only hard requirements. Optional ones are the user's choice, and
				// embedded ones are already inside the jar -- installing those separately
				// is how a profile ends up with two copies of one library.
				if ("required".equals(Http.string(dependency, "dependency_type"))) {
					String projectId = Http.string(dependency, "project_id");
					if (projectId != null) {
						required.add(projectId);
					}
				}
			}
		}

		return new RemoteFile(
				SourceId.MODRINTH,
				Http.string(version, "project_id"),
				Http.string(version, "id"),
				Http.string(version, "version_number"),
				file == null ? null : Http.string(file, "filename"),
				file == null ? null : Http.string(file, "url"),
				file == null ? null : hash(file),
				file == null ? 0L : Http.number(file, "size"),
				List.copyOf(required));
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
		return hashes != null && hashes.isJsonObject()
				? Http.string(hashes.getAsJsonObject(), "sha512") : null;
	}

	@Override
	public String modTitle(String modId) throws IOException, InterruptedException {
		try {
			String title = Http.string(this.http.getObject(API + "/project/" + Http.encode(modId)), "title");
			return title == null ? modId : title;
		} catch (Http.NotFound e) {
			return modId;
		}
	}

	private static List<String> strings(JsonArray array) {
		if (array == null) {
			return List.of();
		}
		List<String> out = new ArrayList<>(array.size());
		array.forEach(e -> out.add(e.getAsString()));
		return List.copyOf(out);
	}
}
