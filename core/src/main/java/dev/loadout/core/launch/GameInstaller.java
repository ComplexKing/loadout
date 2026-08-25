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
		String loader = loaderVersion;
		if (loader == null) {
			JsonArray builds = this.meta.getArray(MetaClient.FABRIC_META + "/versions/loader/" + versionId);
			if (builds.isEmpty()) {
				throw new IOException("Fabric has no loader for Minecraft " + versionId);
			}
			// Newest first, and Fabric marks stable builds; take the newest either way
			// since a version with no stable loader yet is exactly when people want it.
			loader = builds.get(0).getAsJsonObject()
					.getAsJsonObject("loader").get("version").getAsString();
		}

		return this.meta.getObject(MetaClient.FABRIC_META + "/versions/loader/"
				+ versionId + "/" + loader + "/profile/json");
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

		AtomicInteger done = new AtomicInteger();
		int total = pending.size();

		try (ExecutorService pool = Executors.newFixedThreadPool(ASSET_THREADS)) {
			List<Future<?>> tasks = new ArrayList<>(total);
			for (JsonObject object : pending) {
				tasks.add(pool.submit(() -> {
					String hash = object.get("hash").getAsString();
					String shard = hash.substring(0, 2);
					Path destination = assetsDir().resolve("objects").resolve(shard).resolve(hash);
					// Each worker gets its own client: HttpClient is thread safe, but a
					// shared one here would serialise on connection limits.
					new MetaClient().download(ASSET_HOST + shard + "/" + hash,
							destination, object.get("size").getAsLong());

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
}
