package dev.loadout.core.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downloads everything a Minecraft version needs in order to run.
 *
 * <p>Files land in a shared layout under the Loadout home rather than inside any one
 * profile, for the same reason mods do: ten profiles on 26.2 share one client jar and one
 * set of libraries, and assets alone run to several hundred megabytes that would
 * otherwise be duplicated per instance.
 */
public final class GameInstaller {
	private static final String ASSET_HOST = "https://resources.download.minecraft.net/";

	/**
	 * Assets are thousands of tiny files, so throughput is bounded by round trips rather
	 * than bandwidth. Modest parallelism turns a very long install into a short one;
	 * going much wider mostly just annoys the CDN.
	 */
	private static final int ASSET_THREADS = 8;

	/**
	 * How long a chosen Fabric loader build is trusted before looking for a newer one.
	 *
	 * <p>Fabric ships a loader every week or so. Checking twice a day keeps up with that
	 * comfortably while making the check invisible: nobody launches often enough for the
	 * round trip to land more than once in a session.
	 */
	private static final java.time.Duration LOADER_RECHECK = java.time.Duration.ofHours(12);

	private final MetaClient meta;
	private final Path root;

	/** Called as work progresses, for a progress bar or log line. */
	public interface Progress {
		void update(String stage, int done, int total);
	}

	public GameInstaller(Path minecraftRoot) {
		this.meta = new MetaClient();
		this.root = minecraftRoot;
	}

	public Path versionDir(String versionId) {
		return this.root.resolve("versions").resolve(versionId);
	}

	public Path clientJar(String versionId) {
		return versionDir(versionId).resolve(versionId + ".jar");
	}

	public Path librariesDir() {
		return this.root.resolve("libraries");
	}

	public Path assetsDir() {
		return this.root.resolve("assets");
	}

	/** The version's own metadata, fetched once and then cached on disk. */
	public JsonObject versionJson(String versionId) throws IOException, InterruptedException {
		Path cached = versionDir(versionId).resolve(versionId + ".json");
		if (Files.isRegularFile(cached)) {
			return com.google.gson.JsonParser
					.parseString(Files.readString(cached, StandardCharsets.UTF_8))
					.getAsJsonObject();
		}

		JsonObject manifest = this.meta.getObject(MetaClient.VERSION_MANIFEST);
		String url = null;
		for (JsonElement element : manifest.getAsJsonArray("versions")) {
			JsonObject version = element.getAsJsonObject();
			if (versionId.equals(version.get("id").getAsString())) {
				url = version.get("url").getAsString();
				break;
			}
		}
		if (url == null) {
			throw new IOException("Minecraft has no version called '" + versionId + "'");
		}

		String body = this.meta.getString(url);
		Files.createDirectories(cached.getParent());
		Files.writeString(cached, body, StandardCharsets.UTF_8);
		return com.google.gson.JsonParser.parseString(body).getAsJsonObject();
	}

	/** Fabric's launch profile for a given Minecraft version and loader build. */
	public JsonObject fabricProfile(String versionId, String loaderVersion)
			throws IOException, InterruptedException {
		// A pinned loader names an exact, immutable document, so it can be cached with no
		// expiry at all.
		if (loaderVersion != null) {
			return cachedFabricProfile(versionId, loaderVersion);
		}

		Path pick = versionDir(versionId).resolve("fabric-loader.json");
		String remembered = null;
		boolean fresh = false;

		if (Files.isRegularFile(pick)) {
			try {
				JsonObject cached = com.google.gson.JsonParser
						.parseString(Files.readString(pick, StandardCharsets.UTF_8)).getAsJsonObject();
				remembered = cached.get("loader").getAsString();
				fresh = java.time.Instant.parse(cached.get("checkedAt").getAsString())
						.isAfter(java.time.Instant.now().minus(LOADER_RECHECK));
			} catch (RuntimeException e) {
				remembered = null;   // unreadable cache is no cache
			}
		}

		// Two network calls used to happen on every single launch: list the builds, then
		// fetch the profile. Together that was most of a second of a warm start, spent
		// re-learning something that changes about weekly.
		if (fresh) {
			return cachedFabricProfile(versionId, remembered);
		}

		String loader;
		try {
			JsonArray builds = this.meta.getArray(MetaClient.FABRIC_META + "/versions/loader/" + versionId);
			if (builds.isEmpty()) {
				throw new IOException("Fabric has no loader for Minecraft " + versionId);
			}
			// Newest first, and Fabric marks stable builds; take the newest either way
			// since a version with no stable loader yet is exactly when people want it.
			loader = builds.get(0).getAsJsonObject()
					.getAsJsonObject("loader").get("version").getAsString();
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			// Offline with a stale pick is still a playable game. Refusing to start
			// because a version check timed out would be a worse answer than an
			// out-of-date loader.
			if (remembered == null) {
				throw e;
			}
			return cachedFabricProfile(versionId, remembered);
		}

		JsonObject profile = cachedFabricProfile(versionId, loader);

		JsonObject note = new JsonObject();
		note.addProperty("loader", loader);
		note.addProperty("checkedAt", java.time.Instant.now().toString());
		Files.createDirectories(pick.getParent());
		Files.writeString(pick, note.toString(), StandardCharsets.UTF_8);

		return profile;
	}

	/**
	 * The launch profile for one exact loader build, fetched once and then read from disk.
	 *
	 * <p>Immutable by construction: a given Minecraft version and loader version describe
	 * one document that never changes, so there is nothing to invalidate.
	 */
	private JsonObject cachedFabricProfile(String versionId, String loader)
			throws IOException, InterruptedException {
		Path cached = versionDir(versionId).resolve("fabric-" + loader + ".json");

		if (Files.isRegularFile(cached)) {
			try {
				return com.google.gson.JsonParser
						.parseString(Files.readString(cached, StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (RuntimeException e) {
				Files.deleteIfExists(cached);   // truncated by a crash mid-write; refetch
			}
		}

		String body = this.meta.getString(MetaClient.FABRIC_META + "/versions/loader/"
				+ versionId + "/" + loader + "/profile/json");

		Files.createDirectories(cached.getParent());
		Files.writeString(cached, body, StandardCharsets.UTF_8);
		return com.google.gson.JsonParser.parseString(body).getAsJsonObject();
	}

	/**
	 * Installs a version: client jar, libraries, and assets.
	 *
	 * <p>Safe to re-run. Everything already present and the right size is skipped, so a
	 * second install of a shared version costs a few seconds of checking rather than a
	 * re-download.
	 */
	public void install(String versionId, JsonObject versionJson, JsonObject fabricProfile, Progress progress)
			throws IOException, InterruptedException {
		JsonObject client = versionJson.getAsJsonObject("downloads").getAsJsonObject("client");
		progress.update("client", 0, 1);
		this.meta.download(client.get("url").getAsString(), clientJar(versionId), client.get("size").getAsLong());
		progress.update("client", 1, 1);

		List<LibraryResolver.Library> libraries = LibraryResolver.resolve(versionJson, fabricProfile);
		int index = 0;
		for (LibraryResolver.Library library : libraries) {
			progress.update("libraries", index++, libraries.size());
			if (library.url() != null && !library.url().isBlank()) {
				this.meta.download(library.url(), librariesDir().resolve(library.path()), library.size());
			}
		}
		progress.update("libraries", libraries.size(), libraries.size());

		installAssets(versionJson, progress);
	}

	private void installAssets(JsonObject versionJson, Progress progress)
			throws IOException, InterruptedException {
		JsonElement assetIndexElement = versionJson.get("assetIndex");
		if (assetIndexElement == null || !assetIndexElement.isJsonObject()) {
			return;
		}

		JsonObject assetIndex = assetIndexElement.getAsJsonObject();
		String id = assetIndex.get("id").getAsString();

		Path indexFile = assetsDir().resolve("indexes").resolve(id + ".json");
		this.meta.download(assetIndex.get("url").getAsString(), indexFile, assetIndex.get("size").getAsLong());

		JsonObject objects = com.google.gson.JsonParser
				.parseString(Files.readString(indexFile, StandardCharsets.UTF_8))
				.getAsJsonObject()
				.getAsJsonObject("objects");

		List<JsonObject> pending = new ArrayList<>(objects.size());
		objects.entrySet().forEach(entry -> pending.add(entry.getValue().getAsJsonObject()));
		int total = pending.size();

		// Which ones are actually missing, decided with plain file stats before anything
		// touches the network layer.
		//
		// This is the difference between a launch feeling instant and taking two seconds.
		// An index holds about five thousand entries and after the first install every one
		// of them is already on disk, so the answer is always "nothing to do" -- but the
		// question used to be asked by constructing an HttpClient per asset, five thousand
		// of them, each spinning up its own selector thread, to then look at the filesystem
		// and return without sending a request. Measured at 1.9 seconds of every launch.
		List<JsonObject> missing = new ArrayList<>();
		for (JsonObject object : pending) {
			if (!isPresent(object)) {
				missing.add(object);
			}
		}

		progress.update("assets", total - missing.size(), total);
		if (missing.isEmpty()) {
			return;
		}

		// One client per worker rather than one per file. Sharing a single client across
		// the pool would serialise on its connection limits, and thousands of tiny files
		// are bound by round trips rather than bandwidth.
		MetaClient[] clients = new MetaClient[ASSET_THREADS];
		for (int i = 0; i < clients.length; i++) {
			clients[i] = new MetaClient();
		}

		AtomicInteger done = new AtomicInteger(total - missing.size());

		try (ExecutorService pool = Executors.newFixedThreadPool(ASSET_THREADS)) {
			List<Future<?>> tasks = new ArrayList<>(missing.size());
			for (int i = 0; i < missing.size(); i++) {
				JsonObject object = missing.get(i);
				MetaClient client = clients[i % ASSET_THREADS];

				tasks.add(pool.submit(() -> {
					String hash = object.get("hash").getAsString();
					String shard = hash.substring(0, 2);
					client.download(ASSET_HOST + shard + "/" + hash,
							objectPath(hash), object.get("size").getAsLong());

					int count = done.incrementAndGet();
					if (count % 200 == 0 || count == total) {
						progress.update("assets", count, total);
					}
					return null;
				}));
			}

			for (Future<?> task : tasks) {
				try {
					task.get();
				} catch (Exception e) {
					throw new IOException("Asset download failed: " + e.getMessage(), e);
				}
			}
		}

		progress.update("assets", total, total);
	}

	/** Where one asset lives, addressed by the first two characters of its hash. */
	private Path objectPath(String hash) {
		return assetsDir().resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
	}

	/**
	 * Whether an asset is already on disk at the right size.
	 *
	 * <p>The same test {@link MetaClient#download} makes, done here so the overwhelmingly
	 * common answer costs two file stats instead of an HTTP client.
	 */
	private boolean isPresent(JsonObject object) {
		Path path = objectPath(object.get("hash").getAsString());
		if (!Files.isRegularFile(path)) {
			return false;
		}

		long expected = object.get("size").getAsLong();
		try {
			return expected <= 0 || Files.size(path) == expected;
		} catch (IOException e) {
			return false;   // unreadable counts as missing, and the download will say why
		}
	}
}
