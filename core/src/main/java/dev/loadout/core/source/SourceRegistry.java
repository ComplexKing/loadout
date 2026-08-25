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
		List<RemoteMod> found = new ArrayList<>();
		List<String> problems = new ArrayList<>();

		for (ModSource source : available()) {
			try {
				found.addAll(source.search(query, gameVersion, loader, sort, limit, 0));
			} catch (IOException | InterruptedException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				problems.add(source.id().displayName() + ": " + e.getMessage());
			}
		}

		for (ModSource source : unavailable()) {
			problems.add(source.id().displayName() + ": " + source.unavailableReason());
		}

		return new Merged(dedupe(found), List.copyOf(problems));
	}

	/**
	 * @param results merged and deduplicated
	 * @param notes anything that went wrong or is switched off, for showing beneath a listing
	 */
	public record Merged(List<RemoteMod> results, List<String> notes) {
	}

	/**
	 * Collapses the same mod appearing on several sources, keeping the first seen.
	 *
	 * <p>Matching on a simplified title is a heuristic and will occasionally merge two
	 * genuinely different mods with the same name. That is the better failure: showing one
	 * of a pair is a mild annoyance, while showing every popular mod twice makes the whole
	 * listing tiring to read.
	 */
	private static List<RemoteMod> dedupe(List<RemoteMod> mods) {
		Map<String, RemoteMod> byName = new LinkedHashMap<>();
		for (RemoteMod mod : mods) {
			byName.putIfAbsent(simplify(mod.title()), mod);
		}

		List<RemoteMod> merged = new ArrayList<>(byName.values());
		// Most downloaded first, so a merged listing has one obvious ordering rather than
		// being grouped by whichever source answered first.
		merged.sort(Comparator.comparingLong(RemoteMod::downloads).reversed());
		return List.copyOf(merged);
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
