package dev.loadout.core.source;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every source Loadout knows about, and searching across all of them at once.
 *
 * <p>A merged search has to answer a question a single-source one never does: the same
 * mod is usually on both registries, and showing Sodium twice is noise. Results are
 * therefore collapsed by name, preferring Modrinth — not because it is better, but
 * because its files are identified by SHA-512, which is what makes migration and
 * deduplication work later. A CurseForge-only mod still comes through untouched.
 */
public final class SourceRegistry {
	private final Map<SourceId, ModSource> sources = new LinkedHashMap<>();

	public SourceRegistry(String curseForgeKey) {
		// Order matters: it decides which duplicate survives the merge.
		add(new ModrinthSource());
		add(new CurseForgeSource(curseForgeKey));
	}

	private void add(ModSource source) {
		this.sources.put(source.id(), source);
	}

	public ModSource get(SourceId id) {
		return this.sources.get(id);
	}

	public List<ModSource> all() {
		return List.copyOf(this.sources.values());
	}

	public List<ModSource> available() {
		return this.sources.values().stream().filter(ModSource::isAvailable).toList();
	}

	/** Sources that exist but can't be used, with the reason, for telling the user. */
	public List<ModSource> unavailable() {
		return this.sources.values().stream().filter(source -> !source.isAvailable()).toList();
	}

	/**
	 * Searches every available source and merges the results.
	 *
	 * <p>One source failing does not fail the search. A rate limit or an expired key on
	 * CurseForge should not blank out the Modrinth results sitting next to it, so failures
	 * are collected and reported alongside whatever did come back.
	 */
	public Merged search(String query, String gameVersion, String loader,
			ModSource.SortOrder sort, int limit) {
		return search(query, gameVersion, loader, sort, limit, null);
	}

	/**
	 * @param only restrict to one source, or null for all of them. Narrowing to a single
	 *     registry is worth offering because the two differ in ways that matter to a
	 *     person: only Modrinth files carry the hashes migration depends on, and only
	 *     CurseForge hosts mods whose authors forbid third-party downloads.
	 */
	public Merged search(String query, String gameVersion, String loader,
			ModSource.SortOrder sort, int limit, SourceId only) {
		return search(query, gameVersion, loader, sort, limit, only, ContentType.MOD);
	}

	/** @param type what kind of content to look for */
	public Merged search(String query, String gameVersion, String loader,
			ModSource.SortOrder sort, int limit, SourceId only, ContentType type) {
		// Kept per source rather than pooled, because each source has already ranked its own
		// results and that ranking is the thing worth preserving.
		List<List<RemoteMod>> perSource = new ArrayList<>();
		List<String> problems = new ArrayList<>();

		for (ModSource source : available()) {
			if (only != null && source.id() != only) {
				continue;
			}
			try {
				perSource.add(source.search(query, gameVersion, loader, sort, limit, 0, type));
			} catch (IOException | InterruptedException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				problems.add(source.id().displayName() + ": " + e.getMessage());
			}
		}

		for (ModSource source : unavailable()) {
			if (only == null || source.id() == only) {
				problems.add(source.id().displayName() + ": " + source.unavailableReason());
			}
		}

		return new Merged(merge(perSource, sort), List.copyOf(problems));
	}

	/**
	 * Combines several sources' results into one list.
	 *
	 * <p>How they combine depends on what was asked for, and getting this wrong is very
	 * visible. Sorting everything by download count -- which this used to do unconditionally
	 * -- discards each registry's relevance ranking, so a search for "create" returned the
	 * most downloaded mods that merely mentioned it rather than the ones actually named
	 * that. Worse, it buried an entire source: whichever registry hosts the smaller mods
	 * ends up below the fold every time, which looks exactly like that source being broken.
	 *
	 * <p>So relevance interleaves instead, taking one result from each source in turn. Each
	 * source's own ordering survives, and both are represented at the top where people
	 * actually look. An explicit sort by downloads still sorts by downloads, because there
	 * the number is the question rather than a proxy for it.
	 */
	private static List<RemoteMod> merge(List<List<RemoteMod>> perSource, ModSource.SortOrder sort) {
		Map<String, RemoteMod> byName = new LinkedHashMap<>();

		if (sort == ModSource.SortOrder.DOWNLOADS) {
			for (List<RemoteMod> results : perSource) {
				for (RemoteMod mod : results) {
					byName.putIfAbsent(simplify(mod.title()), mod);
				}
			}
			List<RemoteMod> merged = new ArrayList<>(byName.values());
			merged.sort(Comparator.comparingLong(RemoteMod::downloads).reversed());
			return List.copyOf(merged);
		}

		// Round robin. Sources that run out are simply skipped, so a short list from one
		// does not leave gaps or cut the other short.
		int longest = perSource.stream().mapToInt(List::size).max().orElse(0);
		for (int i = 0; i < longest; i++) {
			for (List<RemoteMod> results : perSource) {
				if (i < results.size()) {
					RemoteMod mod = results.get(i);
					byName.putIfAbsent(simplify(mod.title()), mod);
				}
			}
		}
		return List.copyOf(new ArrayList<>(byName.values()));
	}

	/**
	 * @param results merged and deduplicated
	 * @param notes anything that went wrong or is switched off, for showing beneath a listing
	 */
	public record Merged(List<RemoteMod> results, List<String> notes) {
	}

	private static String simplify(String title) {
		return title == null ? "" : title.toLowerCase().replaceAll("[^a-z0-9]", "");
	}

	/** Finds the best file for a mod, asking the source it actually came from. */
	public Optional<RemoteFile> bestFile(SourceId source, String modId, String gameVersion, String loader)
			throws IOException, InterruptedException {
		ModSource resolved = get(source);
		return resolved == null || !resolved.isAvailable()
				? Optional.empty()
				: resolved.bestFile(modId, gameVersion, loader);
	}
}
