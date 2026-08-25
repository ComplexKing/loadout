package dev.loadout.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Finds a mod on Modrinth when its file hash didn't.
 *
 * <p>Hash lookup is exact and should always be tried first, but it only recognises the
 * literal file Modrinth is hosting. A jar from CurseForge, a GitHub release, a Maven
 * repository or a local build is a different set of bytes even when it is the same mod at
 * the same version — so the hash misses and the mod looks unpublished. On a real profile
 * that is a large minority of files, and reporting them all as unknown would make a
 * migration look impossible when it isn't.
 *
 * <p>What this cannot do is be certain. A name match is a strong hint, not proof, so
 * results are reported separately and never acted on without the user agreeing.
 */
public final class ProjectResolver {
	private static final String API = "https://api.modrinth.com/v2";
	private static final String USER_AGENT = "loadout/0.1.0 (Minecraft profile manager)";

	/** Below this, a name is too generic for a prefix match to mean anything. */
	private static final int MIN_MATCH_LENGTH = 5;

	private final HttpClient http;

	public ProjectResolver() {
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	/**
	 * Looks for the project a jar most likely belongs to.
	 *
	 * <p>Tries the mod's own id as a slug first, since plenty of authors use the same
	 * string for both and that match is effectively exact. Falls back to searching the
	 * display name.
	 *
	 * @return the project id, or empty if nothing convincing turned up
	 */
	public Optional<String> resolve(ModJar jar) throws IOException, InterruptedException {
		if (jar.modId() != null) {
			Optional<String> direct = bySlug(jar.modId());
			if (direct.isPresent()) {
				return direct;
			}

			// Fabric ids are snake_case and often carry an API-version suffix that isn't
			// part of the project's name: yet_another_config_lib_v3 is published as
			// yet-another-config-lib.
			String normalised = jar.modId().replace('_', '-').replaceAll("-v\\d+$", "");
			if (!normalised.equals(jar.modId())) {
				Optional<String> slugged = bySlug(normalised);
				if (slugged.isPresent()) {
					return slugged;
				}
			}
		}

		return jar.name() == null ? Optional.empty() : bySearch(jar.name());
	}

	private Optional<String> bySlug(String slug) throws IOException, InterruptedException {
		JsonObject project = getObject(API + "/project/" + encode(slug));
		if (project == null) {
			return Optional.empty();
		}

		JsonElement id = project.get("id");
		return id == null ? Optional.empty() : Optional.of(id.getAsString());
	}

	private Optional<String> bySearch(String name) throws IOException, InterruptedException {
		JsonObject response = getObject(API + "/search?limit=3&query=" + encode(name));
		if (response == null) {
			return Optional.empty();
		}

		JsonArray hits = response.getAsJsonArray("hits");
		if (hits == null || hits.isEmpty()) {
			return Optional.empty();
		}

		// Only accept a result whose title really is this mod. Modrinth returns the
		// closest thing it has, and quietly migrating someone onto a different mod that
		// merely sounds similar is far worse than admitting we don't know.
		//
		// Exact equality is too strict in practice: authors routinely publish under
		// "Name (Acronym)" while the jar declares just "Name" -- YetAnotherConfigLib is
		// listed as "YetAnotherConfigLib (YACL)" under the slug "yacl", so neither its
		// title nor its slug matches what the file says about itself. Accepting a prefix
		// in either direction covers that without opening the door to loose matches.
		String wanted = simplify(name);
		if (wanted.length() < MIN_MATCH_LENGTH) {
			return Optional.empty();  // too short to be distinctive; "lib" would match anything
		}

		for (JsonElement element : hits) {
			JsonObject hit = element.getAsJsonObject();
			JsonElement title = hit.get("title");
			JsonElement projectId = hit.get("project_id");
			if (title == null || projectId == null) {
				continue;
			}

			String candidate = simplify(title.getAsString());
			if (candidate.startsWith(wanted) || wanted.startsWith(candidate)) {
				return Optional.of(projectId.getAsString());
			}
		}

		return Optional.empty();
	}

	/** Newest build of a project for a given target, if there is one. */
	public Optional<ModrinthVersion> latestFor(String projectId, String loader, String gameVersion)
			throws IOException, InterruptedException {
		String url = API + "/project/" + encode(projectId) + "/version"
				+ "?loaders=" + encode("[\"" + loader + "\"]")
				+ "&game_versions=" + encode("[\"" + gameVersion + "\"]");

		String body = get(url);
		if (body == null) {
			return Optional.empty();
		}

		JsonArray versions = JsonParser.parseString(body).getAsJsonArray();
		if (versions.isEmpty()) {
			return Optional.empty();
		}

		// Modrinth returns these newest first.
		return Optional.of(ModrinthVersion.from(versions.get(0).getAsJsonObject()));
	}

	private JsonObject getObject(String url) throws IOException, InterruptedException {
		String body = get(url);
		return body == null ? null : JsonParser.parseString(body).getAsJsonObject();
	}

	/** @return the response body, or null for 404 — which is a normal answer here, not a failure */
	private String get(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", USER_AGENT)
				.timeout(Duration.ofSeconds(30))
				.GET()
				.build();

		HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 404) {
			return null;
		}
		if (response.statusCode() == 429) {
			throw new IOException("Modrinth rate limit reached - wait a moment and try again");
		}
		if (response.statusCode() / 100 != 2) {
			throw new IOException("Modrinth returned " + response.statusCode() + " for " + url);
		}

		return response.body();
	}

	/** Strips case, spaces and punctuation so "YetAnotherConfigLib" matches "Yet Another Config Lib". */
	private static String simplify(String value) {
		return value.toLowerCase().replaceAll("[^a-z0-9]", "");
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	/** Loaders we know how to ask about. */
	public static List<String> knownLoaders() {
		return List.of("fabric", "neoforge", "forge", "quilt");
	}
}
