package dev.loadout.core.source;

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
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared HTTP plumbing, so each source only contains what makes it different. */
public final class Http {
	public static final String USER_AGENT = "loadout/0.1.0 (Minecraft profile manager)";

	private final HttpClient client;
	private final Map<String, String> headers = new LinkedHashMap<>();

	public Http() {
		this.client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		this.headers.put("User-Agent", USER_AGENT);
	}

	public Http header(String name, String value) {
		this.headers.put(name, value);
		return this;
	}

	public JsonObject getObject(String url) throws IOException, InterruptedException {
		return JsonParser.parseString(get(url)).getAsJsonObject();
	}

	public String get(String url) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(30))
				.GET();
		this.headers.forEach(builder::header);

		HttpResponse<String> response = this.client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		int status = response.statusCode();

		// These three get their own messages because they are the ones a user can act on:
		// a bad key, too many requests, or a mod that simply isn't there.
		if (status == 401 || status == 403) {
			throw new IOException("Rejected by the server (" + status + ") - check the API key");
		}
		if (status == 429) {
			throw new IOException("Rate limited - wait a moment and try again");
		}
		if (status == 404) {
			throw new NotFound(url);
		}
		if (status / 100 != 2) {
			throw new IOException("HTTP " + status + " for " + url);
		}

		return response.body();
	}

	/** Same handling as {@link #get}, for the endpoints that only answer to a POST. */
	public String post(String url, String jsonBody) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(30))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
		this.headers.forEach(builder::header);

		HttpResponse<String> response = this.client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		int status = response.statusCode();

		if (status == 401 || status == 403) {
			throw new IOException("Rejected by the server (" + status + ") - check the API key");
		}
		if (status == 429) {
			throw new IOException("Rate limited - wait a moment and try again");
		}
		if (status == 404) {
			throw new NotFound(url);
		}
		if (status / 100 != 2) {
			throw new IOException("HTTP " + status + " for " + url);
		}

		return response.body();
	}

	/** Distinguishes "no such thing" from "something went wrong", which callers treat differently. */
	public static final class NotFound extends IOException {
		public NotFound(String url) {
			super("Not found: " + url);
		}
	}

	public static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	public static String string(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}

	public static long number(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsLong() : 0L;
	}
}
