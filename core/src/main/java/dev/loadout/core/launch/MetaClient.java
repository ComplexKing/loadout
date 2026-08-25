package dev.loadout.core.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Fetches launch metadata and files from Mojang and FabricMC.
 *
 * <p>Straight to the source, with no metadata service in between. That's slightly more
 * work than consuming someone's pre-chewed index, and it means Loadout keeps working when
 * a third party's server doesn't, never goes stale on a new Minecraft release, and can't
 * be a channel for anyone to hand our users a modified library list.
 */
public final class MetaClient {
	public static final String VERSION_MANIFEST =
			"https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
	public static final String FABRIC_META = "https://meta.fabricmc.net/v2";

	private static final String USER_AGENT = "loadout/0.1.0 (Minecraft profile manager)";

	private final HttpClient http;

	public MetaClient() {
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(20))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	public JsonObject getObject(String url) throws IOException, InterruptedException {
		return JsonParser.parseString(getString(url)).getAsJsonObject();
	}

	public JsonArray getArray(String url) throws IOException, InterruptedException {
		return JsonParser.parseString(getString(url)).getAsJsonArray();
	}

	public String getString(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", USER_AGENT)
				.timeout(Duration.ofMinutes(2))
				.GET()
				.build();

		HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new IOException("HTTP " + response.statusCode() + " for " + url);
		}
		return response.body();
	}

	/**
	 * Downloads to a path, skipping the transfer when the file is already correct.
	 *
	 * <p>Installing a Minecraft version is well over a hundred files, and most of them are
	 * shared between versions. Checking size before re-downloading turns a second install
	 * from minutes into seconds.
	 *
	 * @param expectedSize from the metadata, or 0 when unknown
	 * @return true if something was actually downloaded
	 */
	public boolean download(String url, Path destination, long expectedSize)
			throws IOException, InterruptedException {
		if (Files.isRegularFile(destination)
				&& (expectedSize <= 0 || Files.size(destination) == expectedSize)) {
			return false;
		}

		Files.createDirectories(destination.getParent());
		Path temp = destination.resolveSibling(destination.getFileName() + ".part");

		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", USER_AGENT)
				.timeout(Duration.ofMinutes(15))
				.GET()
				.build();

		HttpResponse<InputStream> response = this.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() / 100 != 2) {
			throw new IOException("HTTP " + response.statusCode() + " downloading " + url);
		}

		try (InputStream body = response.body()) {
			Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
		}

		// Move into place only once complete, so an interrupted download can never be
		// mistaken for a finished file on the next run.
		Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
		return true;
	}
}
