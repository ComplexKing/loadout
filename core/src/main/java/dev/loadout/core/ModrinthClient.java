package dev.loadout.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The parts of Modrinth's API that matter for managing a profile.
 *
 * <p>Everything here is keyed on file hash rather than mod name or file name. That's the
 * detail that makes the whole thing work on real installs: people rename jars, browsers
 * append "(1)", and two different mods can ship the same filename. A SHA-512 resolves to
 * exactly one published file or to nothing at all.
 */
public final class ModrinthClient {
	private static final String API = "https://api.modrinth.com/v2";

	/**
	 * Modrinth asks projects to identify themselves and to include contact details, and
	 * throttles anonymous traffic harder. Being a good citizen here is also what keeps us
	 * from being rate limited on a large profile.
	 */
	private static final String USER_AGENT = "loadout/0.1.0 (Minecraft profile manager)";

	/** Requests are capped server-side; chunking keeps large profiles working. */
	private static final int BATCH = 100;

	private final HttpClient http;
	private final Gson gson = new Gson();

	public ModrinthClient() {
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	/**
	 * Works out what each file actually is.
	 *
	 * @return hash to the published version it belongs to. Hashes with no entry are files
	 *     Modrinth has never seen -- a local build, a CurseForge-only mod, or something
	 *     hand-modified.
	 */
	public Map<String, ModrinthVersion> identify(Collection<String> hashes) throws IOException, InterruptedException {
		return batched(hashes, chunk -> {
			JsonObject body = new JsonObject();
			body.add("hashes", this.gson.toJsonTree(chunk));
			body.addProperty("algorithm", "sha512");
			return post(API + "/version_files", body);
		});
	}

	/**
	 * Asks what each of these files would be on a different Minecraft version.
	 *
	 * <p>This is the core of profile migration. Modrinth resolves each hash to its
	 * project and then picks that project's newest build matching the target, so a
	 * profile can be moved wholesale rather than mod by mod.
	 *
	 * @param loaders e.g. {@code ["fabric"]}
	 * @param gameVersions e.g. {@code ["1.21.1"]}
	 * @return hash to the version it becomes. A hash absent from the result has no build
	 *     for the target, which is the answer that actually matters when deciding whether
	 *     a migration is possible at all.
	 */
	public Map<String, ModrinthVersion> findUpdates(Collection<String> hashes, List<String> loaders,
			List<String> gameVersions) throws IOException, InterruptedException {
		return batched(hashes, chunk -> {
			JsonObject body = new JsonObject();
			body.add("hashes", this.gson.toJsonTree(chunk));
			body.addProperty("algorithm", "sha512");
			body.add("loaders", this.gson.toJsonTree(loaders));
			body.add("game_versions", this.gson.toJsonTree(gameVersions));
			return post(API + "/version_files/update", body);
		});
	}

	private interface ChunkCall {
		JsonObject apply(List<String> chunk) throws IOException, InterruptedException;
	}

	private Map<String, ModrinthVersion> batched(Collection<String> hashes, ChunkCall call)
			throws IOException, InterruptedException {
		List<String> all = new ArrayList<>(hashes);
		Map<String, ModrinthVersion> result = new HashMap<>();

		for (int start = 0; start < all.size(); start += BATCH) {
			List<String> chunk = all.subList(start, Math.min(start + BATCH, all.size()));
			JsonObject response = call.apply(chunk);
			response.entrySet().forEach(entry -> {
				if (entry.getValue().isJsonObject()) {
					result.put(entry.getKey(), ModrinthVersion.from(entry.getValue().getAsJsonObject()));
				}
			});
		}

		return result;
	}

	private JsonObject post(String url, JsonObject body) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", USER_AGENT)
				.header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(45))
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();

		HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 429) {
			throw new IOException("Modrinth rate limit reached - wait a moment and try again");
		}
		if (response.statusCode() / 100 != 2) {
			throw new IOException("Modrinth returned " + response.statusCode() + " for " + url);
		}

		return JsonParser.parseString(response.body()).getAsJsonObject();
	}
}
