package dev.loadout.core.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Which Minecraft versions exist, from Mojang's own manifest.
 *
 * <p>Exists so nobody has to type a version string. Getting one slightly wrong -- "1.21"
 * for "1.21.1", a snapshot id misremembered -- produces a profile that silently matches
 * no mods at all, and the failure shows up much later as an empty search rather than as a
 * typo. A list you pick from cannot be misspelled.
 */
public final class VersionCatalog {
	/**
	 * How long a fetched manifest is reused.
	 *
	 * <p>The manifest is a few hundred kilobytes and changes when Mojang ships something,
	 * so refetching it per keystroke would be wasteful and never refetching it would mean
	 * a long-running launcher never sees a new snapshot. An hour is comfortably inside
	 * both bounds.
	 */
	private static final Duration FRESH_FOR = Duration.ofHours(1);

	/**
	 * @param id the launcher id, e.g. "1.21.1" or "26w05a"
	 * @param type "release", "snapshot", "old_beta" or "old_alpha"
	 * @param releasedAt ISO-8601, for showing how old a version is
	 */
	public record Entry(String id, String type, String releasedAt) {
		public boolean isRelease() {
			return "release".equals(this.type);
		}
	}

	/**
	 * @param versions newest first, as Mojang orders them
	 * @param latestRelease the id to offer by default
	 * @param latestSnapshot the newest snapshot id
	 */
	public record Catalog(List<Entry> versions, String latestRelease, String latestSnapshot) {
	}

	private final MetaClient meta;

	// Shared across requests: the answer is the same for everyone, and a launcher opening
	// its "new instance" dialog twice should not fetch twice.
	private static volatile Catalog cached;
	private static volatile Instant fetchedAt;

	public VersionCatalog(MetaClient meta) {
		this.meta = meta;
	}

	public Catalog fetch() throws IOException, InterruptedException {
		Catalog existing = cached;
		Instant at = fetchedAt;
		if (existing != null && at != null && Duration.between(at, Instant.now()).compareTo(FRESH_FOR) < 0) {
			return existing;
		}

		JsonObject manifest = this.meta.getObject(MetaClient.VERSION_MANIFEST);

		JsonObject latest = manifest.getAsJsonObject("latest");
		String latestRelease = latest == null ? null : string(latest, "release");
		String latestSnapshot = latest == null ? null : string(latest, "snapshot");

		JsonArray versions = manifest.getAsJsonArray("versions");
		List<Entry> entries = new ArrayList<>(versions == null ? 0 : versions.size());
		if (versions != null) {
			for (JsonElement element : versions) {
				JsonObject version = element.getAsJsonObject();
				entries.add(new Entry(
						string(version, "id"),
						string(version, "type"),
						string(version, "releaseTime")));
			}
		}

		Catalog catalog = new Catalog(List.copyOf(entries), latestRelease, latestSnapshot);
		cached = catalog;
		fetchedAt = Instant.now();
		return catalog;
	}

	private static String string(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}
}
