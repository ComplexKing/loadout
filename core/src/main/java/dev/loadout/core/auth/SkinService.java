package dev.loadout.core.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Reading and changing an account's skin.
 *
 * <p>Fetched through this process rather than by the interface pointing an image tag at a
 * skin service. Two reasons: the texture host is plain HTTP, which a page with a sane
 * content policy will not load; and the third-party avatar services everyone reaches for
 * would learn the UUID of every account anybody signs in with. Mojang already knows -- a
 * rendering service has no need to.
 */
public final class SkinService {
	private static final String SESSION_PROFILE =
			"https://sessionserver.mojang.com/session/minecraft/profile/";
	private static final String SKIN_ENDPOINT =
			"https://api.minecraftservices.com/minecraft/profile/skins";

	/** Skins are 64x64; anything much larger is not one. */
	private static final int MAX_SKIN_BYTES = 512 * 1024;

	private final HttpClient http;

	public SkinService() {
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(20))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	/**
	 * @param pngBase64 the skin texture itself, so the interface can render it without
	 *     reaching a third party
	 * @param variant "classic" or "slim", which decides arm width
	 * @param capeBase64 the active cape, or null
	 */
	public record Skin(String pngBase64, String variant, String capeBase64) {
	}

	/**
	 * The current skin for a UUID.
	 *
	 * <p>Uses the public session endpoint, so it works for any account and needs no token
	 * -- including one that is signed out, which is what the account list wants.
	 */
	public Optional<Skin> fetch(String uuid) throws IOException, InterruptedException {
		String bare = uuid.replace("-", "");
		JsonObject profile = getJson(SESSION_PROFILE + bare);

		JsonArray properties = profile.getAsJsonArray("properties");
		if (properties == null || properties.isEmpty()) {
			return Optional.empty();
		}

		String encoded = string(properties.get(0).getAsJsonObject(), "value");
		if (encoded == null) {
			return Optional.empty();
		}

		JsonObject textures = JsonParser
				.parseString(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8))
				.getAsJsonObject()
				.getAsJsonObject("textures");
		if (textures == null) {
			return Optional.empty();
		}

		JsonObject skin = textures.getAsJsonObject("SKIN");
		if (skin == null) {
			return Optional.empty();
		}

		String url = string(skin, "url");
		if (url == null) {
			return Optional.empty();
		}

		// The variant lives in metadata and is simply absent for classic arms.
		JsonObject metadata = skin.getAsJsonObject("metadata");
		String variant = metadata != null && "slim".equals(string(metadata, "model"))
				? "slim" : "classic";

		JsonObject cape = textures.getAsJsonObject("CAPE");
		String capeUrl = cape == null ? null : string(cape, "url");

		return Optional.of(new Skin(
				base64Of(url),
				variant,
				capeUrl == null ? null : base64Of(capeUrl)));
	}

	/**
	 * Replaces the account's skin.
	 *
	 * <p>Needs a live Minecraft access token, which means an account that has just been
	 * refreshed rather than one merely stored.
	 *
	 * @param variant "classic" or "slim"
	 */
	public void upload(String accessToken, Path png, String variant)
			throws IOException, InterruptedException {
		byte[] image = Files.readAllBytes(png);
		if (image.length > MAX_SKIN_BYTES) {
			throw new IOException("That file is too large to be a skin.");
		}
		if (!looksLikePng(image)) {
			throw new IOException("A skin has to be a PNG.");
		}

		// Multipart by hand: two fields, and pulling in an HTTP library for one request
		// would be a poor trade.
		String boundary = "loadout" + Long.toHexString(RandomGenerator.getDefault().nextLong());
		var body = new java.io.ByteArrayOutputStream();

		body.write(("--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"variant\"\r\n\r\n"
				+ variant + "\r\n").getBytes(StandardCharsets.UTF_8));

		body.write(("--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
				+ "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(image);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

		HttpResponse<String> response = this.http.send(
				HttpRequest.newBuilder(URI.create(SKIN_ENDPOINT))
						.header("Authorization", "Bearer " + accessToken)
						.header("Content-Type", "multipart/form-data; boundary=" + boundary)
						.timeout(Duration.ofSeconds(60))
						.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
						.build(),
				HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() / 100 != 2) {
			throw new IOException("Minecraft refused the skin (" + response.statusCode() + ").");
		}
	}

	/** The first eight bytes of every PNG, checked so a renamed file fails here not there. */
	private static boolean looksLikePng(byte[] data) {
		byte[] magic = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
		if (data.length < magic.length) {
			return false;
		}
		for (int i = 0; i < magic.length; i++) {
			if (data[i] != magic[i]) {
				return false;
			}
		}
		return true;
	}

	private String base64Of(String url) throws IOException, InterruptedException {
		HttpResponse<byte[]> response = this.http.send(
				HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());

		if (response.statusCode() / 100 != 2 || response.body().length > MAX_SKIN_BYTES) {
			throw new IOException("Could not read the texture at " + url);
		}
		return Base64.getEncoder().encodeToString(response.body());
	}

	private JsonObject getJson(String url) throws IOException, InterruptedException {
		HttpResponse<String> response = this.http.send(
				HttpRequest.newBuilder(URI.create(url))
						.header("Accept", "application/json")
						.timeout(Duration.ofSeconds(30))
						.GET().build(),
				HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() / 100 != 2) {
			throw new IOException("Profile lookup returned " + response.statusCode());
		}

		JsonElement parsed = JsonParser.parseString(response.body());
		return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
	}

	private static String string(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}
}
