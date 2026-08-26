package dev.loadout.launcher.serve;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * A local HTTP API over the same core the CLI drives.
 *
 * <p>This exists so a desktop UI can be written in something other than Swing. The
 * interesting half of Loadout is deciding what a profile can do, and none of that should
 * have to be reimplemented per front end -- so the jar keeps the logic and speaks JSON,
 * and a UI process drives it.
 *
 * <h2>Why a local server needs real access control</h2>
 *
 * <p>A server on 127.0.0.1 is reachable by every program on the machine, and -- more
 * awkwardly -- by any web page the user has open, since a page may send requests to
 * localhost. An unprotected API here would let a visited website enumerate profiles,
 * install mods or start the game. Four things prevent that:
 *
 * <ul>
 *   <li><b>Loopback only.</b> Never bound to a routable address, so nothing off the
 *       machine can reach it at all.
 *   <li><b>A bearer token</b> generated per run and printed to stdout for the parent
 *       process. A web page cannot read that, and requiring it in a header means any
 *       cross-origin attempt becomes a preflighted request.
 *   <li><b>No CORS headers, ever.</b> Preflights are refused, so a browser blocks the
 *       real request before it is sent and could not read the response regardless.
 *   <li><b>Host header checking</b>, which is what stops DNS rebinding -- an attacker
 *       pointing a name they control at 127.0.0.1 to make a page's requests same-origin.
 * </ul>
 */
public final class ApiServer {
	/** Hosts a legitimate local client will address us by. Anything else is rebinding. */
	private static final Set<String> ALLOWED_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

	private final HttpServer http;
	private final Jobs jobs = new Jobs();
	private final Routes routes;
	private final byte[] token;
	private final List<Route> table = new ArrayList<>();

	private ApiServer(int port, dev.loadout.core.LoadoutHome home) throws IOException {
		this.token = newToken();
		this.routes = new Routes(home, this.jobs);

		// Port 0 asks the OS for a free port. Fixed ports collide with whatever else the
		// user runs, and there is no reason to insist on one when the parent process is
		// told which was chosen.
		this.http = HttpServer.create(
				new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);

		// Without an executor every request is served on the accept thread, one at a time,
		// so a slow search would freeze the whole UI.
		this.http.setExecutor(Executors.newFixedThreadPool(8, runnable -> {
			Thread thread = new Thread(runnable, "loadout-api");
			thread.setDaemon(true);
			return thread;
		}));

		register();
		this.http.createContext("/", this::dispatch);
	}

	// -- routing ---------------------------------------------------------------------

	/** A handler plus the method and path shape that select it. */
	private record Route(String method, String[] pattern, Handler handler) {
	}

	@FunctionalInterface
	private interface Handler {
		void handle(HttpExchange exchange, Map<String, String> path, Query query) throws IOException;
	}

	private void add(String method, String pattern, Handler handler) {
		this.table.add(new Route(method, split(pattern), handler));
	}

	private void register() {
		add("GET", "/health", (exchange, path, query) -> Json.send(exchange, 200, this.routes.health()));

		add("GET", "/sources", (exchange, path, query) -> Json.send(exchange, 200, this.routes.sources(query)));
		add("PUT", "/settings/curseforge-key", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.setCurseForgeKey(Json.readObject(exchange))));

		add("GET", "/profiles", (exchange, path, query) -> Json.send(exchange, 200, this.routes.profiles()));
		add("POST", "/profiles", (exchange, path, query) ->
				Json.send(exchange, 201, this.routes.createProfile(Json.readObject(exchange))));
		add("GET", "/profiles/{name}", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.profile(path.get("name"))));
		add("DELETE", "/profiles/{name}", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.deleteProfile(path.get("name"))));

		add("POST", "/profiles/{name}/mods", (exchange, path, query) ->
				Json.send(exchange, 202, this.routes.installMod(path.get("name"), Json.readObject(exchange))));
		add("DELETE", "/profiles/{name}/mods/{file}", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.removeMod(path.get("name"), path.get("file"))));
		add("PUT", "/profiles/{name}/mods/{file}", (exchange, path, query) ->
				Json.send(exchange, 200,
						this.routes.toggleMod(path.get("name"), path.get("file"), Json.readObject(exchange))));

		add("GET", "/profiles/{name}/icons", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.modIcons(path.get("name"))));

		add("GET", "/profiles/{name}/worlds", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.worlds(path.get("name"))));
		add("GET", "/profiles/{name}/servers", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.servers(path.get("name"))));
		add("GET", "/profiles/{name}/packs", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.packs(path.get("name"), query)));
		add("GET", "/profiles/{name}/screenshots", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.screenshots(path.get("name"))));
		add("GET", "/profiles/{name}/logs", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.logs(path.get("name"))));
		add("GET", "/profiles/{name}/log", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.logTail(path.get("name"), query)));

		add("POST", "/profiles/{name}/duplicate", (exchange, path, query) ->
				Json.send(exchange, 201, this.routes.duplicate(path.get("name"), Json.readObject(exchange))));
		add("POST", "/profiles/{name}/export", (exchange, path, query) ->
				Json.send(exchange, 202, this.routes.export(path.get("name"), Json.readObject(exchange))));

		add("GET", "/profiles/{name}/options", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.instanceOptions(path.get("name"))));
		add("PUT", "/profiles/{name}/options", (exchange, path, query) ->
				Json.send(exchange, 200,
						this.routes.setInstanceOptions(path.get("name"), Json.readObject(exchange))));

		add("GET", "/settings/game", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.gameDefaults()));
		add("PUT", "/settings/game", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.setGameDefaults(Json.readObject(exchange))));

		add("GET", "/profiles/{name}/snapshots", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.snapshots(path.get("name"))));
		add("POST", "/profiles/{name}/rollback", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.rollback(path.get("name"), Json.readObject(exchange))));

		add("POST", "/profiles/{name}/migrate", (exchange, path, query) ->
				Json.send(exchange, 202, this.routes.migrate(path.get("name"), Json.readObject(exchange))));
		add("POST", "/profiles/{name}/launch", (exchange, path, query) ->
				Json.send(exchange, 202, this.routes.launch(path.get("name"), Json.readObject(exchange))));

		add("GET", "/search", (exchange, path, query) -> Json.send(exchange, 200, this.routes.search(query)));
		add("GET", "/versions", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.versions(query)));
		add("GET", "/accounts", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.accounts()));
		add("POST", "/accounts/signin", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.beginSignIn()));
		add("POST", "/accounts/complete", (exchange, path, query) ->
				Json.send(exchange, 202, this.routes.completeSignIn(Json.readObject(exchange))));
		add("POST", "/accounts/{uuid}/primary", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.setPrimaryAccount(path.get("uuid"))));
		add("DELETE", "/accounts/{uuid}", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.removeAccount(path.get("uuid"))));

		add("GET", "/java", (exchange, path, query) -> Json.send(exchange, 200, this.routes.javaInstalls()));
		add("GET", "/minecraft/versions", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.minecraftVersions()));

		add("GET", "/jobs", (exchange, path, query) -> Json.send(exchange, 200, this.routes.jobs()));
		add("GET", "/jobs/{id}", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.job(path.get("id"))));
		add("POST", "/jobs/{id}/cancel", (exchange, path, query) ->
				Json.send(exchange, 200, this.routes.cancelJob(path.get("id"))));

		add("GET", "/events", (exchange, path, query) -> streamEvents(exchange));
	}

	private void dispatch(HttpExchange exchange) throws IOException {
		try {
			if (!isLocalRequest(exchange)) {
				// Deliberately terse. A caller who reached the wrong server does not need to
				// be told what the right Host header would have been.
				Json.sendError(exchange, 403, "Forbidden");
				return;
			}

			// Refused rather than answered. Replying to a preflight is what would let a web
			// page send the real request, so the absence of a handler here is the defence.
			if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
				Json.sendError(exchange, 405, "Cross-origin requests are not supported");
				return;
			}

			if (!isAuthorised(exchange)) {
				Json.sendError(exchange, 401, "Missing or invalid API token");
				return;
			}

			String[] segments = split(exchange.getRequestURI().getPath());
			boolean pathMatched = false;

			for (Route route : this.table) {
				Map<String, String> bindings = match(route.pattern(), segments);
				if (bindings == null) {
					continue;
				}
				pathMatched = true;
				if (route.method().equals(exchange.getRequestMethod())) {
					route.handler().handle(exchange, bindings, new Query(exchange.getRequestURI()));
					return;
				}
			}

			// 405 when the path exists but the verb is wrong, which is the difference
			// between a typo in a client's URL and a typo in its method.
			Json.sendError(exchange, pathMatched ? 405 : 404,
					pathMatched ? "Method not allowed" : "No such endpoint");
		} catch (ApiException e) {
			Json.sendError(exchange, e.status(), e.getMessage());
		} catch (Exception e) {
			String message = e.getMessage();
			Json.sendError(exchange, 500,
					message == null || message.isBlank() ? e.getClass().getSimpleName() : message);
		} finally {
			exchange.close();
		}
	}

	private static String[] split(String path) {
		String trimmed = path.startsWith("/") ? path.substring(1) : path;
		if (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed.isEmpty() ? new String[0] : trimmed.split("/");
	}

	/** @return the bound placeholders, or null when this pattern does not describe the path */
	private static Map<String, String> match(String[] pattern, String[] segments) {
		if (pattern.length != segments.length) {
			return null;
		}

		Map<String, String> bindings = new HashMap<>();
		for (int i = 0; i < pattern.length; i++) {
			String expected = pattern[i];
			if (expected.startsWith("{") && expected.endsWith("}")) {
				// Profile names and file names contain spaces and punctuation, so a segment
				// is only meaningful after decoding.
				bindings.put(expected.substring(1, expected.length() - 1), decode(segments[i]));
			} else if (!expected.equals(segments[i])) {
				return null;
			}
		}
		return bindings;
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	/** Query string access, since HttpExchange only hands over the raw text. */
	static final class Query {
		private final Map<String, String> values = new HashMap<>();

		Query(URI uri) {
			String raw = uri.getRawQuery();
			if (raw == null || raw.isEmpty()) {
				return;
			}
			for (String pair : raw.split("&")) {
				int equals = pair.indexOf('=');
				if (equals < 0) {
					this.values.put(decode(pair), "");
				} else {
					this.values.put(decode(pair.substring(0, equals)), decode(pair.substring(equals + 1)));
				}
			}
		}

		String get(String key, String fallback) {
			String value = this.values.get(key);
			return value == null || value.isEmpty() ? fallback : value;
		}

		String require(String key) {
			String value = get(key, null);
			if (value == null) {
				throw new ApiException(400, "Missing required query parameter: " + key);
			}
			return value;
		}

		int getInt(String key, int fallback) {
			String value = get(key, null);
			if (value == null) {
				return fallback;
			}
			try {
				return Integer.parseInt(value);
			} catch (NumberFormatException e) {
				throw new ApiException(400, key + " must be a number, got: " + value);
			}
		}
	}

	// -- access control --------------------------------------------------------------

	private static byte[] newToken() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encode(bytes);
	}

	/**
	 * Checks the Host header names this machine.
	 *
	 * <p>Binding to loopback is not by itself enough. In a DNS rebinding attack the user
	 * visits a page on a domain the attacker controls, whose DNS then resolves to
	 * 127.0.0.1 -- so the browser treats requests to this server as same-origin and sends
	 * them freely. The requests still carry the attacker's domain in Host, which is what
	 * gives them away.
	 */
	private static boolean isLocalRequest(HttpExchange exchange) {
		String host = exchange.getRequestHeaders().getFirst("Host");
		if (host == null) {
			// HTTP/1.1 requires Host. Absent means something handmade, not a browser.
			return true;
		}

		String withoutPort = host;
		int colon = host.lastIndexOf(':');
		if (colon > 0 && host.indexOf(':') == colon) {
			withoutPort = host.substring(0, colon);   // ipv4 or name with a port
		} else if (host.startsWith("[")) {
			int close = host.indexOf(']');
			withoutPort = close > 0 ? host.substring(0, close + 1) : host;
		}

		return ALLOWED_HOSTS.contains(withoutPort.toLowerCase());
	}

	private boolean isAuthorised(HttpExchange exchange) {
		String header = exchange.getRequestHeaders().getFirst("Authorization");
		if (header == null || !header.startsWith("Bearer ")) {
			return false;
		}

		byte[] presented = header.substring("Bearer ".length()).trim()
				.getBytes(StandardCharsets.UTF_8);
		// Constant-time, so the comparison cannot be used to recover the token one byte at
		// a time. Cheap here, and the alternative is a subtle mistake to have made.
		return MessageDigest.isEqual(presented, this.token);
	}

	// -- server-sent events ----------------------------------------------------------

	/**
	 * Streams job progress until the client goes away.
	 *
	 * <p>Server-sent events rather than websockets: the traffic is one-directional, every
	 * command already has a normal endpoint, and SSE needs no handshake or extra
	 * dependency.
	 */
	private void streamEvents(HttpExchange exchange) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.getResponseHeaders().set("Connection", "keep-alive");
		// Length 0 with a 200 means chunked, which is what keeps the response open.
		exchange.sendResponseHeaders(200, 0);

		OutputStream out = exchange.getResponseBody();
		Object writeLock = new Object();

		Runnable unsubscribe = this.jobs.subscribe(event -> {
			try {
				synchronized (writeLock) {
					write(out, event.toJson());
				}
			} catch (IOException e) {
				// The client hung up. Rethrowing unchecked makes Jobs drop this listener.
				throw new java.io.UncheckedIOException(e);
			}
		});

		try {
			// Anything already running is replayed first, so a client that connects late --
			// or reconnects -- sees in-flight work instead of waiting for its next tick.
			synchronized (writeLock) {
				for (Jobs.Job job : this.jobs.all()) {
					if (job.state() == Jobs.State.RUNNING) {
						write(out, new Jobs.Event("progress", job, null).toJson());
					}
				}
			}

			// Idle connections get closed by proxies and sleeping laptops alike; a comment
			// line is the SSE way to keep one alive and to notice when it has died.
			while (!Thread.currentThread().isInterrupted()) {
				Thread.sleep(15_000);
				synchronized (writeLock) {
					out.write(":keepalive\n\n".getBytes(StandardCharsets.UTF_8));
					out.flush();
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException | java.io.UncheckedIOException e) {
			// Normal: the UI closed or reloaded.
		} finally {
			unsubscribe.run();
			try {
				out.close();
			} catch (IOException ignored) {
				// Already gone.
			}
		}
	}

	private static void write(OutputStream out, JsonObject event) throws IOException {
		String payload = "data: " + Json.GSON.toJson(event) + "\n\n";
		out.write(payload.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	// -- lifecycle -------------------------------------------------------------------

	/**
	 * Starts the API and announces itself on stdout.
	 *
	 * <p>The handshake is a single JSON line rather than a file. A file would have to live
	 * somewhere predictable, be readable by every process owned by the user, and be cleaned
	 * up after a crash; a pipe to the parent that spawned this process has none of those
	 * problems and closes itself.
	 */
	public static void start(dev.loadout.core.LoadoutHome home, int port) throws IOException {
		ApiServer server = new ApiServer(port, home);
		server.http.start();

		JsonObject hello = new JsonObject();
		hello.addProperty("ready", true);
		hello.addProperty("port", server.http.getAddress().getPort());
		hello.addProperty("token", new String(server.token, StandardCharsets.UTF_8));
		hello.addProperty("pid", ProcessHandle.current().pid());

		System.out.println(Json.GSON.toJson(hello));
		System.out.flush();

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			server.jobs.shutdown();
			server.http.stop(0);
		}));

		// The server threads are daemons, so without this the process would exit as soon as
		// main returned and take the API with it.
		try {
			Thread.currentThread().join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
