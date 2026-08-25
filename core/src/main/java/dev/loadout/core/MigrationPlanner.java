package dev.loadout.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Works out whether a profile can move to another Minecraft version, and what breaks. */
public final class MigrationPlanner {
	private final ModrinthClient modrinth;
	private final ProjectResolver resolver;

	public MigrationPlanner(ModrinthClient modrinth) {
		this(modrinth, new ProjectResolver());
	}

	public MigrationPlanner(ModrinthClient modrinth, ProjectResolver resolver) {
		this.modrinth = modrinth;
		this.resolver = resolver;
	}

	/**
	 * @param mods everything in the profile, enabled or not
	 * @param targetGameVersion the Minecraft version to move to
	 * @param loader the mod loader, e.g. "fabric"
	 */
	public MigrationPlan plan(List<ModJar> mods, String targetGameVersion, String loader)
			throws IOException, InterruptedException {
		// Disabled mods are excluded from the network calls entirely. They can't break a
		// migration, and on a large profile they're often most of the folder.
		Set<String> activeHashes = new HashSet<>();
		for (ModJar mod : mods) {
			if (mod.enabled()) {
				activeHashes.add(mod.sha512());
			}
		}

		Map<String, ModrinthVersion> identified = this.modrinth.identify(activeHashes);
		Map<String, ModrinthVersion> updates =
				this.modrinth.findUpdates(activeHashes, List.of(loader), List.of(targetGameVersion));

		List<MigrationPlan.Entry> entries = new ArrayList<>(mods.size());
		for (ModJar mod : mods) {
			MigrationPlan.Entry entry = classify(mod, targetGameVersion, identified, updates);

			// A hash miss only means Modrinth isn't hosting this exact file -- the same
			// mod from CurseForge, a GitHub release or a Maven repo is different bytes.
			// Try to find it by identity before writing it off as unpublished.
			if (entry.status() == MigrationPlan.Status.UNKNOWN) {
				entry = resolveByName(mod, targetGameVersion, loader);
			}

			entries.add(entry);
		}

		return new MigrationPlan(targetGameVersion, loader, List.copyOf(entries));
	}

	private MigrationPlan.Entry resolveByName(ModJar mod, String targetGameVersion, String loader)
			throws IOException, InterruptedException {
		var project = this.resolver.resolve(mod);
		if (project.isEmpty()) {
			return new MigrationPlan.Entry(mod, MigrationPlan.Status.UNKNOWN, null);
		}

		var version = this.resolver.latestFor(project.get(), loader, targetGameVersion);
		if (version.isEmpty()) {
			// The project exists but has nothing for the target. That is a real blocker,
			// and a more useful answer than "we couldn't find it".
			return new MigrationPlan.Entry(mod, MigrationPlan.Status.NO_BUILD, null);
		}

		return new MigrationPlan.Entry(mod, MigrationPlan.Status.LIKELY, version.get());
	}

	private static MigrationPlan.Entry classify(ModJar mod, String targetGameVersion,
			Map<String, ModrinthVersion> identified, Map<String, ModrinthVersion> updates) {
		if (!mod.enabled()) {
			return new MigrationPlan.Entry(mod, MigrationPlan.Status.DISABLED, null);
		}

		ModrinthVersion current = identified.get(mod.sha512());
		ModrinthVersion target = updates.get(mod.sha512());

		// Already fine. Worth separating from READY so the report can say "nothing to do"
		// instead of listing a file that wouldn't actually change.
		if (current != null && current.gameVersions().contains(targetGameVersion)) {
			return new MigrationPlan.Entry(mod, MigrationPlan.Status.ALREADY_SUPPORTED, current);
		}

		if (target != null) {
			// Modrinth can return the same file back when it's already the best match.
			if (target.sha512() != null && target.sha512().equalsIgnoreCase(mod.sha512())) {
				return new MigrationPlan.Entry(mod, MigrationPlan.Status.ALREADY_SUPPORTED, target);
			}
			return new MigrationPlan.Entry(mod, MigrationPlan.Status.READY, target);
		}

		// Known project, no build for the target -- this is a real blocker and the user
		// has to decide whether to drop the mod or abandon the move.
		if (current != null) {
			return new MigrationPlan.Entry(mod, MigrationPlan.Status.NO_BUILD, current);
		}

		// Never seen by Modrinth. Might still be fine -- plenty of mods live only on
		// CurseForge, and a locally built jar is by definition unpublished -- so this is
		// reported as needing a human rather than as a failure.
		return new MigrationPlan.Entry(mod, MigrationPlan.Status.UNKNOWN, null);
	}
}
