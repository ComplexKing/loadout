package dev.loadout.core;

import java.util.List;

/**
 * What would happen if a profile were moved to a different Minecraft version.
 *
 * <p>The point of producing this as a report rather than just doing it: moving a profile
 * is usually blocked by two or three mods out of eighty, and the only question that
 * matters is which ones. Every launcher will happily update mods one at a time; none of
 * them will tell you up front that the move is impossible because one mod you care about
 * never shipped a build.
 */
public record MigrationPlan(
		String targetGameVersion,
		String loader,
		List<Entry> entries
) {
	public enum Status {
		/** A build exists for the target version. */
		READY,
		/** The installed file already supports the target; nothing to do. */
		ALREADY_SUPPORTED,
		/** Known to Modrinth, but the project has no build for the target. */
		NO_BUILD,
		/**
		 * Found by name rather than by hash, so a build exists but we are less than
		 * certain it is the same mod. Kept separate from READY because acting on a wrong
		 * guess silently replaces one mod with another.
		 */
		LIKELY,
		/** Not published on Modrinth at all -- a local build, CurseForge-only, or modified. */
		UNKNOWN,
		/** Currently disabled, so it can't block anything. */
		DISABLED
	}

	/**
	 * @param jar the file as it exists now
	 * @param target what it would become, or null when there's nothing to move to
	 */
	public record Entry(ModJar jar, Status status, ModrinthVersion target) {
		public boolean blocks() {
			return this.status == Status.NO_BUILD || this.status == Status.UNKNOWN;
		}
	}

	public List<Entry> withStatus(Status status) {
		return this.entries.stream().filter(e -> e.status() == status).toList();
	}

	/** Enabled mods that would stop this migration from being clean. */
	public List<Entry> blockers() {
		return this.entries.stream().filter(Entry::blocks).toList();
	}

	/** True when every enabled mod has somewhere to go. */
	public boolean isClean() {
		return blockers().isEmpty();
	}

	/** Matched by name rather than hash; a human should confirm these before applying. */
	public List<Entry> needsConfirming() {
		return withStatus(Status.LIKELY);
	}

	/** Files that would actually be replaced. */
	public List<Entry> changes() {
		return withStatus(Status.READY);
	}
}
