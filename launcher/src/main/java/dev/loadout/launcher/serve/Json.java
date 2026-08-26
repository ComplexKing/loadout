package dev.loadout.launcher.serve;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

/** Reading requests and writing responses, so handlers deal in objects rather than bytes. */
final class Json {
	/** Nulls are dropped rather than serialised, which keeps optional fields absent instead of null. */
	static final Gson GSON = new GsonBuilder().create();

	/**
	 * A request body larger than this is refused unread.
	 *
	 * <p>The server is loopback-only, so this is not really a defence against an attacker
	 * -- it is a defence against a bug in the client sending an unbounded stream and this
	 * process growing until it dies.
	 */
	private static final int MAX_BODY_BYTES = 1 << 20;

	private Json() {
	}

	static JsonObject readObject(HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody()) {
			byte[] bytes = in.readNBytes(MAX_BODY_BYTES);
			if (bytes.length == 0) {
				return new JsonObject();
			}
			JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) {
				throw new ApiException(400, "Request body must be a JSON object");
			}
			return parsed.getAsJsonObject();
		} catch (com.google.gson.JsonSyntaxException | IllegalStateException e) {
			throw new ApiException(400, "Malformed JSON: " + e.getMessage());
		}
	}

	static String requireString(JsonObject body, String field) {
		String value = optionalString(body, field, null);
		if (value == null || value.isBlank()) {
			throw new ApiException(400, "Missing required field: " + field);
		}
		return value;
	}

	static String optionalString(JsonObject body, String field, String fallback) {
		JsonElement element = body.get(field);
		return element == null || element.isJsonNull() ? fallback : element.getAsString();
	}

	static int optionalInt(JsonObject body, String field, int fallback) {
		JsonElement element = body.get(field);
		return element == null || element.isJsonNull() ? fallback : element.getAsInt();
	}

	static boolean optionalBoolean(JsonObject body, String field, boolean fallback) {
		JsonElement element = body.get(field);
		return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
	}

	static JsonObject object() {
		return new JsonObject();
	}

	/** Maps a list into a JSON array, so handlers do not each write the same loop. */
	static <T> JsonArray arrayOf(List<T> items, Function<T, JsonElement> mapper) {
		JsonArray array = new JsonArray();
		for (T item : items) {
			array.add(mapper.apply(item));
		}
		return array;
	}

	static JsonArray stringsOf(List<String> items) {
		JsonArray array = new JsonArray();
		items.forEach(array::add);
		return array;
	}

	static void send(HttpExchange exchange, int status, JsonElement body) throws IOException {
		byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);

		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		// No Access-Control-Allow-Origin, deliberately. Without it a browser refuses to let
		// a page read any response from this server, which is most of what stops a random
		// website probing the API while the launcher is open.
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(status, bytes.length);

		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	static void sendError(HttpExchange exchange, int status, String message) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("error", message);
		body.addProperty("status", status);
		send(exchange, status, body);
	}
}
