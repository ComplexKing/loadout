package dev.loadout.core.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Modrinth. Open API, no credentials, SHA-512 file hashes. */
public final class ModrinthSource implements ModSource {
	private static final String API = "https://api.modrinth.com/v2";

	private final Http http = new Http();

	@Override
	public SourceId id() {
		return SourceId.MODRINTH;
	}

	@Override
	public boolean isAvailable() {
		return true;  // no key required
	}

	@Override
	public String unavailableReason() {
		return null;
	}

	@Override
	public List<RemoteMod> search(String query, String gameVersion, String loader,
			SortOrder sort, int limit, int offset) throws IOException, InterruptedException {
		return search(query, gameVersion, loader, sort, limit, offset, ContentType.MOD);
	}

	@Override
	public List<RemoteMod> search(String query, String gameVersion, String loader,
			SortOrder sort, int limit, int offset, ContentType type)
			throws IOException, InterruptedException {
		ContentType kind = type == null ? ContentType.MOD : type;

		// Modrinth hosts no worlds. Asking for project_type:world returns an error, so the
		// honest answer is that this source has none rather than that the search failed.
		if (kind == ContentType.WORLD) {
			return List.of();
		}

		// The loader facet is omitted for resource packs and shaders. They work with any
		// loader, and asking for "fabric" resource packs matches nothing at all.
		String facets = "[[\"project_type:" + kind.key() + "\"]"
				+ ",[\"versions:" + gameVersion + "\"]"
				+ (kind.usesLoader() ? ",[\"categories:" + loader + "\"]" : "")
				+ "]";

		StringBuilder url = new StringBuilder(API + "/search?limit=" + limit + "&offset=" + offset)
				.append("&index=").append(Http.encode(indexFor(sort)))
				.append("&facets=").append(Http.encode(facets));
		if (query != null && !query.isBlank()) {
			url.append("&query=").append(Http.encode(query));
		}

		JsonArray hits = this.http.getObject(url.toString()).getAsJsonArray("hits");
		List<RemoteMod> results = new ArrayList<>(hits.size());
		for (JsonElement element : hits) {
			JsonObject hit = element.getAsJsonObject();
			results.add(new RemoteMod(
					SourceId.MODRINTH,
					Http.string(hit, "project_id"),
					Http.string(hit, "slug"),
					Http.string(hit, "title"),
					Http.string(hit, "description"),
					Http.string(hit, "author"),
					Http.number(hit, "downloads"),
					Http.string(hit, "icon_url"),
					strings(hit.getAsJsonArray("display_categories"))));
		}
		return results;
	}

	private static String indexFor(SortOrder sort) {
		return switch (sort) {
			case DOWNLOADS -> "downloads";
			case UPDATED -> "updated";
			case NEWEST -> "newest";
			default -> "relevance";
		};
	}

	@Override
	public Optional<RemoteFile> bestFile(String modId, String gameVersion, String loader)
			throws IOException, InterruptedException {
		// Modrinth returns newest first, so the best build is the first that fits.
		List<RemoteFile> all = versions(modId, gameVersion, loader);
		return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
	}

	@Override
	public List<RemoteFile> versions(String modId, String gameVersion, String loader)
			throws IOException, InterruptedException {
		// The loader filter is omitted rather than sent empty. A null here previously became
		// the literal string "null" inside the JSON array, which matched no build at all --
		// so every resource pack and shader reported as having no version that fits.
		StringBuilder url = new StringBuilder(API + "/project/" + Http.encode(modId) + "/version?");
		if (loader != null && !loader.isBlank()) {
			url.append("loaders=").append(Http.encode("[\"" + loader + "\"]")).append('&');
		}
		if (gameVersion != null && !gameVersion.isBlank()) {
			url.append("game_versions=").append(Http.encode("[\"" + gameVersion + "\"]"));
		}

		JsonArray versions;
		try {
			versions = JsonParser.parseString(this.http.get(url.toString())).getAsJsonArray();
		} catch (Http.NotFound e) {
			return List.of();
		}

		List<RemoteFile> files = new ArrayList<>(versions.size());
		for (JsonElement element : versions) {
			files.add(toFile(element.getAsJsonObject()));
		}
		return List.copyOf(files);
	}

	@Override
	public Optional<RemoteFile> fileForVersion(String modId, String versionId)
			throws IOException, InterruptedException {
		try {
			JsonObject version = this.http.getObject(API + "/version/" + Http.encode(versionId));
			return Optional.of(toFile(version));
		} catch (Http.NotFound e) {
			return Optional.empty();
		}
	}

	private static RemoteFile toFile(JsonObject version) {
		JsonObject file = primaryFile(version.getAsJsonArray("files"));

		List<String> required = new ArrayList<>();
		JsonArray dependencies = version.getAsJsonArray("dependencies");
		if (dependencies != null) {
			for (JsonElement element : dependencies) {
				JsonObject dependency = element.getAsJsonObject();
				// Only hard requirements. Optional ones are the user's choice, and
				// embedded ones are already inside the jar -- installing those separately
				// is how a profile ends up with two copies of one library.
				if ("required".equals(Http.string(dependency, "dependency_type"))) {
					String projectId = Http.string(dependency, "project_id");
					if (projectId != null) {
						required.add(projectId);
					}
				}
			}
		}

		return new RemoteFile(
				SourceId.MODRINTH,
				Http.string(version, "project_id"),
				Http.string(version, "id"),
				Http.string(version, "version_number"),
				file == null ? null : Http.string(file, "filename"),
				file == null ? null : Http.string(file, "url"),
				file == null ? null : hash(file),
				file == null ? 0L : Http.number(file, "size"),
				List.copyOf(required));
	}

	private static JsonObject primaryFile(JsonArray files) {
		if (files == null || files.isEmpty()) {
			return null;
		}
		for (JsonElement element : files) {
			JsonObject file = element.getAsJsonObject();
			JsonElement primary = file.get("primary");
			if (primary != null && primary.isJsonPrimitive() && primary.getAsBoolean()) {
				return file;
			}
		}
		return files.get(0).getAsJsonObject();
	}

	private static String hash(JsonObject file) {
		JsonElement hashes = file.get("hashes");
		return hashes != null && hashes.isJsonObject()
				? Http.string(hashes.getAsJsonObject(), "sha512") : null;
	}

	@Override
	public java.util.Map<String, String> icons(List<String> modIds)
			throws IOException, InterruptedException {
		if (modIds.isEmpty()) {
			return java.util.Map.of();
		}

		StringBuilder ids = new StringBuilder("[");
		for (int i = 0; i < modIds.size(); i++) {
			ids.append(i > 0 ? "," : "").append('"').append(modIds.get(i)).append('"');
		}
		ids.append(']');

		java.util.Map<String, String> found = new java.util.HashMap<>();
		try {
			JsonArray projects = JsonParser
					.parseString(this.http.get(API + "/projects?ids=" + Http.encode(ids.toString())))
					.getAsJsonArray();

			for (JsonElement element : projects) {
				JsonObject project = element.getAsJsonObject();
				String icon = Http.string(project, "icon_url");
				if (icon == null || icon.isBlank()) {
					continue;
				}
				// Keyed by both, because a profile may store either depending on whether the
				// mod was installed by slug or reached as another mod's dependency.
				String id = Http.string(project, "id");
				String slug = Http.string(project, "slug");
				if (id != null) {
					found.put(id, icon);
				}
				if (slug != null) {
					found.put(slug, icon);
				}
			}
		} catch (Http.NotFound e) {
			return java.util.Map.of();
		}
		return found;
	}

	@Override
	public java.util.Optional<RemoteDetails> details(String modId)
			throws IOException, InterruptedException {
		JsonObject project;
		try {
			project = this.http.getObject(API + "/project/" + Http.encode(modId));
		} catch (Http.NotFound e) {
			return java.util.Optional.empty();
		}

		List<RemoteDetails.GalleryImage> gallery = new ArrayList<>();
		JsonArray images = project.getAsJsonArray("gallery");
		if (images != null) {
			for (JsonElement element : images) {
				JsonObject image = element.getAsJsonObject();
				String url = Http.string(image, "url");
				if (url != null) {
					gallery.add(new RemoteDetails.GalleryImage(url,
							Http.string(image, "title"), Http.string(image, "description")));
				}
			}
		}

		List<RemoteDetails.Link> links = new ArrayList<>();
		addLink(links, "Source", Http.string(project, "source_url"));
		addLink(links, "Issues", Http.string(project, "issues_url"));
		addLink(links, "Wiki", Http.string(project, "wiki_url"));
		addLink(links, "Discord", Http.string(project, "discord_url"));

		List<String> categories = new ArrayList<>();
		JsonArray tags = project.getAsJsonArray("categories");
		if (tags != null) {
			for (JsonElement tag : tags) {
				categories.add(tag.getAsString());
			}
		}

		JsonObject licence = project.getAsJsonObject("license");

		return java.util.Optional.of(new RemoteDetails(
				SourceId.MODRINTH,
				Http.string(project, "id"),
				Http.string(project, "slug"),
				Http.string(project, "title"),
				Http.string(project, "description"),
				null,   // Modrinth returns team members from a separate endpoint
				Http.number(project, "downloads"),
				Http.number(project, "followers"),
				Http.string(project, "icon_url"),
				Http.string(project, "body"),
				"markdown",
				licence == null ? null : Http.string(licence, "name"),
				Http.string(project, "updated"),
				List.copyOf(categories),
				List.copyOf(gallery),
				List.copyOf(links)));
	}

	private static void addLink(List<RemoteDetails.Link> links, String label, String url) {
		if (url != null && !url.isBlank()) {
			links.add(new RemoteDetails.Link(label, url));
		}
	}

	@Override
	public String modTitle(String modId) throws IOException, InterruptedException {
		try {
			String title = Http.string(this.http.getObject(API + "/project/" + Http.encode(modId)), "title");
			return title == null ? modId : title;
		} catch (Http.NotFound e) {
			return modId;
		}
	}

	private static List<String> strings(JsonArray array) {
		if (array == null) {
			return List.of();
		}
		List<String> out = new ArrayList<>(array.size());
		array.forEach(e -> out.add(e.getAsString()));
		return List.copyOf(out);
	}
}
