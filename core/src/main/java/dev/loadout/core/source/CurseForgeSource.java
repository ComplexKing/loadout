package dev.loadout.core.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CurseForge.
 *
 * <p>Three things differ from Modrinth and all three are visible to the user:
 *
 * <ul>
 *   <li><b>It needs an API key.</b> Issued from the CurseForge developer console and
 *       sent on every request. Without one this source reports itself unavailable rather
 *       than failing each query.
 *   <li><b>Authors can forbid third-party downloads.</b> When they have, the API returns
 *       the file with no download url. That is a deliberate decision by the author, so
 *       the right response is to link them to the website, not to work around it.
 *   <li><b>Loader and version filtering is by numeric id</b>, not by name, so the strings
 *       a profile stores have to be translated before they mean anything here.
 * </ul>
 */
public final class CurseForgeSource implements ModSource {
	private static final String API = "https://api.curseforge.com/v1";

	/** CurseForge's id for Minecraft. */
	private static final int MINECRAFT = 432;
	/** The "Mods" class within Minecraft, as opposed to modpacks or resource packs. */
	private static final int MOD_CLASS = 6;

	private final String apiKey;
	private final Http http;

	public CurseForgeSource(String apiKey) {
		this.apiKey = apiKey;
		this.http = new Http();
		if (apiKey != null && !apiKey.isBlank()) {
			this.http.header("x-api-key", apiKey);
		}
	}

	@Override
	public SourceId id() {
		return SourceId.CURSEFORGE;
	}

	@Override
	public boolean isAvailable() {
		return this.apiKey != null && !this.apiKey.isBlank();
	}

	@Override
	public String unavailableReason() {
		return isAvailable() ? null
				: "Needs an API key from console.curseforge.com. Add it with: loadout key curseforge <key>";
	}

	/** CurseForge's numeric loader ids. */
	private static int loaderId(String loader) {
		return switch (loader == null ? "" : loader.toLowerCase()) {
			case "forge" -> 1;
			case "fabric" -> 4;
			case "quilt" -> 5;
			case "neoforge" -> 6;
			default -> 0;  // 0 means "any", which is better than refusing to search
		};
	}

	@Override
	public List<RemoteMod> search(String query, String gameVersion, String loader,
			SortOrder sort, int limit, int offset) throws IOException, InterruptedException {
		if (!isAvailable()) {
			return List.of();
		}

		StringBuilder url = new StringBuilder(API + "/mods/search?gameId=" + MINECRAFT)
				.append("&classId=").append(MOD_CLASS)
				.append("&pageSize=").append(limit)
				.append("&index=").append(offset)
				.append("&sortField=").append(sortField(sort))
				.append("&sortOrder=desc");

		if (gameVersion != null && !gameVersion.isBlank()) {
			url.append("&gameVersion=").append(Http.encode(gameVersion));
		}
		int modLoader = loaderId(loader);
		if (modLoader > 0) {
			url.append("&modLoaderType=").append(modLoader);
		}
		if (query != null && !query.isBlank()) {
			url.append("&searchFilter=").append(Http.encode(query));
		}

		JsonArray data = this.http.getObject(url.toString()).getAsJsonArray("data");
		List<RemoteMod> results = new ArrayList<>();
		if (data == null) {
			return results;
		}

		for (JsonElement element : data) {
			JsonObject mod = element.getAsJsonObject();
			results.add(new RemoteMod(
					SourceId.CURSEFORGE,
					String.valueOf(Http.number(mod, "id")),
					Http.string(mod, "slug"),
					Http.string(mod, "name"),
					Http.string(mod, "summary"),
					firstAuthor(mod),
					Http.number(mod, "downloadCount"),
					logoUrl(mod),
					categories(mod)));
		}
		return results;
	}

	/** CurseForge's sortField is numeric: 1 featured, 2 popularity, 3 updated, 6 total downloads. */
	private static int sortField(SortOrder sort) {
		return switch (sort) {
			case DOWNLOADS -> 6;
			case UPDATED -> 3;
			case NEWEST -> 11;
			default -> 2;
		};
	}

	private static String firstAuthor(JsonObject mod) {
		JsonArray authors = mod.getAsJsonArray("authors");
		if (authors == null || authors.isEmpty()) {
			return null;
		}
		return Http.string(authors.get(0).getAsJsonObject(), "name");
	}

	private static String logoUrl(JsonObject mod) {
		JsonElement logo = mod.get("logo");
		return logo != null && logo.isJsonObject() ? Http.string(logo.getAsJsonObject(), "url") : null;
	}

	private static List<String> categories(JsonObject mod) {
		JsonArray array = mod.getAsJsonArray("categories");
		if (array == null) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (JsonElement element : array) {
			String name = Http.string(element.getAsJsonObject(), "name");
			if (name != null) {
				out.add(name);
			}
		}
		return List.copyOf(out);
	}

	@Override
	public Optional<RemoteFile> bestFile(String modId, String gameVersion, String loader)
			throws IOException, InterruptedException {
		if (!isAvailable()) {
			return Optional.empty();
		}

		StringBuilder url = new StringBuilder(API + "/mods/" + Http.encode(modId) + "/files?pageSize=20");
		if (gameVersion != null && !gameVersion.isBlank()) {
			url.append("&gameVersion=").append(Http.encode(gameVersion));
		}
		int modLoader = loaderId(loader);
		if (modLoader > 0) {
			url.append("&modLoaderType=").append(modLoader);
		}

		JsonArray files;
		try {
			files = this.http.getObject(url.toString()).getAsJsonArray("data");
		} catch (Http.NotFound e) {
			return Optional.empty();
		}
		if (files == null || files.isEmpty()) {
			return Optional.empty();
		}

		// Newest first by file id, which increases monotonically.
		JsonObject newest = null;
		long best = Long.MIN_VALUE;
		for (JsonElement element : files) {
			JsonObject file = element.getAsJsonObject();
			long id = Http.number(file, "id");
			if (id > best) {
				best = id;
				newest = file;
			}
		}

		return newest == null ? Optional.empty() : Optional.of(toFile(modId, newest));
	}

	private static RemoteFile toFile(String modId, JsonObject file) {
		List<String> required = new ArrayList<>();
		JsonArray dependencies = file.getAsJsonArray("dependencies");
		if (dependencies != null) {
			for (JsonElement element : dependencies) {
				JsonObject dependency = element.getAsJsonObject();
				// relationType 3 is "required dependency" in CurseForge's scheme.
				if (Http.number(dependency, "relationType") == 3) {
					required.add(String.valueOf(Http.number(dependency, "modId")));
				}
			}
		}

		// downloadUrl is null when the author has opted out of third-party distribution.
		// Carried through as null so the installer can say so plainly.
		return new RemoteFile(
				SourceId.CURSEFORGE,
				modId,
				String.valueOf(Http.number(file, "id")),
				Http.string(file, "displayName"),
				Http.string(file, "fileName"),
				Http.string(file, "downloadUrl"),
				null,  // CurseForge publishes md5/sha1 fingerprints, not sha512
				Http.number(file, "fileLength"),
				List.copyOf(required));
	}

	@Override
	public String modTitle(String modId) throws IOException, InterruptedException {
		if (!isAvailable()) {
			return modId;
		}
		try {
			JsonObject data = this.http.getObject(API + "/mods/" + Http.encode(modId))
					.getAsJsonObject("data");
			String name = Http.string(data, "name");
			return name == null ? modId : name;
		} catch (IOException e) {
			return modId;
		}
	}
}
