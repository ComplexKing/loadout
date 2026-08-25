package dev.loadout.core;

/**
 * A profile as it was at a moment in time.
 *
 * <p>Only a list of hashes, so it's cheap enough to take before every change without
 * anyone deciding whether it's worth it — which is the only way an undo is ever there
 * when it's actually needed.
 *
 * @param id timestamp-based, so snapshots sort chronologically by name alone
 * @param takenAt ISO-8601 instant
 * @param reason what was about to happen, e.g. "migrate to 1.21.1"
 * @param profile the full state
 */
public record Snapshot(String id, String takenAt, String reason, Profile profile) {
}
