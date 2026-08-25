package dev.loadout.core.browse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.loadout.core.ModrinthVersion;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Searching Modrinth for mods, and finding the right build of one. */
public final class ModBrowser {
	private static final String API = "https://api.modrinth.com/v2";
	private static final String USER_AGENT = "loadout/0.1.0 (Minecraft profile manager)";

	private final HttpClient http;

	public ModBrowser() {
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	/**
	 * Searches for mods compatible with a profile.
	 *
	 * <p>Filtered by game version and loader server-side rather than locally. Searching
	 * everything and then hiding what doesn't fit produces a page that's mostly empty and
	 * a paginator that lies about how many results there are.
	 *
	 * @param query free text; empty returns the most popular for the filters
	 * @param sort one of Modrinth's index names, e.g. relevance, downloads, updated
	 */
	public List<SearchResult> search(String query, String gameVersion, String loader,
			String sort, int limit, int offset) throws IOException, InterruptedException {
		String facets = "[[\"project_type:mod\"]"
				+ ",[\"versions:" + gameVersion + "\"]"
				+ ",[\"categories:" + loader + "\"]]";

		StringBuilder url = new StringBuilder(API + "/search?limit=" + limit + "&offset=" + offset);
		url.append("&index=").append(encode(sort == null ? "relevance" : sort));
		url.append("&facets=").append(encode(facets));
		if (query != null && !query.isBlank()) {
			url.append("&query=").append(encode(query));
		}

		JsonObject response = JsonParser.parseString(get(url.toString())).getAsJsonObject();
		JsonArray hits = response.getAsJsonArray("hits");

		List<SearchResult> results = new ArrayList<>(hits.size());
		for (JsonElement element : hits) {
			results.add(toResult(element.getAsJsonObject()));
		}
		return results;
	}

	private static SearchResult toResult(JsonObject hit) {
		List<String> versions = strings(hit.getAsJsonArray("versions"));
		return new SearchResult(
				string(hit, "project_id"),
				string(hit, "slug"),
				string(hit, "title"),
				string(hit, "description"),
				string(hit, "author"),
				intOf(hit, "downloads"),
				intOf(hit, "follows"),
				string(hit, "icon_url"),
				strings(hit.getAsJsonArray("display_categories")),
				versions.isEmpty() ? null : versions.get(versions.size() - 1)
		);
	}

	/** The newest build of a project for a given target. */
	public Optional<ModrinthVersion> bestVersion(String projectId, String gameVersion, String loader)
			throws IOException, InterruptedException {
		String url = API + "/project/" + encode(projectId) + "/version"
				+ "?loaders=" + encode("[\"" + loader + "\"]")
				+ "&game_versions=" + encode("[\"" + gameVersion + "\"]");

		JsonArray versions = JsonParser.parseString(get(url)).getAsJsonArray();
		if (versions.isEmpty()) {
			return Optional.empty();
		}
		// Modrinth returns newest first.
		return Optional.of(ModrinthVersion.from(versions.get(0).getAsJsonObject()));
	}

	/**
	 * The projects a version requires.
	 *
	 * <p>Only hard requirements. Modrinth also records optional and incompatible
	 * relationships, and embedded ones for libraries already inside the jar — installing
	 * an embedded dependency separately is how you end up with two copies of the same
	 * library and a loader that refuses to start.
	 *
	 * @return required project ids
	 */
	public List<String> requiredDependencies(String versionId) throws IOException, InterruptedException {
		JsonObject version = JsonParser.parseString(get(API + "/version/" + encode(versionId))).getAsJsonObject();
		JsonArray dependencies = version.getAsJsonArray("dependencies");
		if (dependencies == null) {
			return List.of();
		}

		List<String> required = new ArrayList<>();
		for (JsonElement element : dependencies) {
			JsonObject dependency = element.getAsJsonObject();
			if (!"required".equals(string(dependency, "dependency_type"))) {
				continue;
			}

			String projectId = string(dependency, "project_id");
			if (projectId != null) {
				required.add(projectId);
			}
		}
		return required;
	}

	/** A project's display name, for reporting what a dependency actually is. */
	public String projectTitle(String projectId) throws IOException, InterruptedException {
		JsonObject project = JsonParser.parseString(get(API + "/project/" + encode(projectId))).getAsJsonObject();
		String title = string(project, "title");
		return title == null ? projectId : title;
	}

	private String get(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", USER_AGENT)
				.timeout(Duration.ofSeconds(30))
				.GET()
				.build();

		HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 429) {
			throw new IOException("Modrinth rate limit reached - wait a moment and try again");
		}
		if (response.statusCode() / 100 != 2) {
			throw new IOException("Modrinth returned " + response.statusCode());
		}
		return response.body();
	}

	private static String string(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}

	private static int intOf(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsInt() : 0;
	}

	private static List<String> strings(JsonArray array) {
		if (array == null) {
			return List.of();
		}
		List<String> out = new ArrayList<>(array.size());
		array.forEach(e -> out.add(e.getAsString()));
		return List.copyOf(out);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
