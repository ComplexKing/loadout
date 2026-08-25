package dev.loadout.launcher;

import dev.loadout.core.LoadoutHome;
import dev.loadout.core.MigrationPlan;
import dev.loadout.core.MigrationPlanner;
import dev.loadout.core.ModJar;
import dev.loadout.core.ModScanner;
import dev.loadout.core.ModrinthClient;
import dev.loadout.core.Profile;
import dev.loadout.core.ProfileManager;
import dev.loadout.core.Snapshot;
import dev.loadout.core.launch.GameInstaller;
import dev.loadout.core.launch.GameSession;
import dev.loadout.core.launch.JavaLocator;
import dev.loadout.core.launch.LaunchBuilder;
import dev.loadout.core.launch.LogRedactor;
import dev.loadout.core.auth.AccountStore;
import dev.loadout.core.auth.StoredAccount;
import dev.loadout.core.browse.ModBrowser;
import dev.loadout.core.browse.ModInstaller;
import dev.loadout.core.browse.SearchResult;
import dev.loadout.launcher.ui.MainWindow;
import dev.loadout.launcher.ui.Theme;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command line front end.
 *
 * <p>The CLI comes before any UI on purpose: the interesting work is deciding what a
 * profile can do, not drawing it, and a terminal command is testable against a real mods
 * folder from day one. The eventual UI and the in-game mod both drive the same core.
 */
public final class Main {
	private Main() {
	}

	public static void main(String[] args) throws Exception {
		// No arguments means someone double-clicked the jar, so show the window. The CLI
		// stays the full-featured half; the window is the approachable half over the
		// same core.
		if (args.length == 0 || args[0].equals("gui")) {
			launchGui();
			return;
		}

		List<String> rest = new ArrayList<>(List.of(args).subList(1, args.length));
		boolean apply = rest.remove("--apply");
		boolean includeLikely = rest.remove("--include-likely");
		boolean overwrite = rest.remove("--overwrite");

		LoadoutHome home = LoadoutHome.defaultHome();
		ProfileManager manager = new ProfileManager(home);

		switch (args[0]) {
			case "scan" -> {
				need(rest, 1, "scan <mods-dir>");
				scan(Path.of(rest.get(0)));
			}
			case "plan" -> {
				need(rest, 2, "plan <mods-dir> <target-mc-version> [loader]");
				List<ModJar> mods = ModScanner.scan(Path.of(rest.get(0)));
				report(planFor(mods, rest.get(1), loaderOr(rest, 2)), rest.get(1));
			}
			case "import" -> {
				need(rest, 3, "import <mods-dir> <profile> <mc-version> [loader]");
				Profile profile = manager.importFrom(
						Path.of(rest.get(0)), rest.get(1), rest.get(2), loaderOr(rest, 3));
				System.out.printf("Imported %d mods into profile '%s' (%s, %s)%n",
						profile.mods().size(), profile.name(), profile.minecraftVersion(), profile.loader());
				System.out.println("  " + home.modsDir(profile.name()));
			}
			case "list" -> list(home);
			case "migrate" -> {
				need(rest, 2, "migrate <profile> <target-mc-version> [--apply] [--include-likely]");
				migrate(home, manager, rest.get(0), rest.get(1), apply, includeLikely);
			}
			case "snapshots" -> {
				need(rest, 1, "snapshots <profile>");
				snapshots(home, rest.get(0));
			}
			case "rollback" -> {
				need(rest, 2, "rollback <profile> <snapshot-id>");
				Profile restored = manager.rollback(rest.get(0), rest.get(1));
				System.out.printf("Rolled '%s' back to %s - %d mods, Minecraft %s%n",
						restored.name(), rest.get(1), restored.mods().size(), restored.minecraftVersion());
			}
			case "sync" -> {
				need(rest, 2, "sync <from-profile> <to-profile> [--overwrite]");
				int copied = manager.syncSettings(rest.get(0), rest.get(1), overwrite);
				System.out.printf("Copied %d config files from '%s' to '%s'%s%n",
						copied, rest.get(0), rest.get(1), overwrite ? "" : " (skipped ones that already existed)");
			}
			case "search" -> {
				need(rest, 2, "search <profile> <query>");
				search(home, rest.get(0), String.join(" ", rest.subList(1, rest.size())));
			}
			case "add" -> {
				need(rest, 2, "add <profile> <project-id-or-slug>");
				add(home, rest.get(0), rest.get(1));
			}
			case "remove" -> {
				need(rest, 2, "remove <profile> <file-name>");
				boolean removed = new ModInstaller(home).remove(rest.get(0), rest.get(1));
				System.out.println(removed ? "Removed " + rest.get(1) : "No such mod: " + rest.get(1));
			}
			case "toggle" -> {
				need(rest, 3, "toggle <profile> <file-name> <on|off>");
				boolean on = rest.get(2).equalsIgnoreCase("on");
				boolean changed = new ModInstaller(home).setEnabled(rest.get(0), rest.get(1), on);
				System.out.println(changed
						? rest.get(1) + " is now " + (on ? "enabled" : "disabled")
						: "No such mod: " + rest.get(1));
			}
			case "java" -> javas();
			case "install" -> {
				need(rest, 1, "install <mc-version> [loader-version]");
				install(home, rest.get(0), rest.size() > 1 ? rest.get(1) : null);
			}
			case "run" -> {
				need(rest, 1, "run <profile> [username]");
				run(home, rest.get(0), rest.size() > 1 ? rest.get(1) : "Player");
			}
			case "prune" -> prune(home);
			case "preview" -> {
				// Renders the interface to PNGs without opening a window, so the design
				// can be reviewed without taking over someone's screen.
				need(rest, 1, "preview <output-dir>");
				dev.loadout.launcher.ui.Preview.render(Path.of(rest.get(0)), home);
				System.exit(0);
			}
			case "help" -> usage();
			default -> usage();
		}
	}

	private static void launchGui() {
		if (java.awt.GraphicsEnvironment.isHeadless()) {
			System.err.println("No display available. Run 'loadout help' for the command line.");
			usage();
			return;
		}

		javax.swing.SwingUtilities.invokeLater(() -> {
			Theme.install();
			new MainWindow(LoadoutHome.defaultHome()).setVisible(true);
		});
	}

	private static void usage() {
		System.out.println("""
				loadout - Minecraft profile manager

				  scan <mods-dir>
				  plan <mods-dir> <target-mc-version> [loader]
				      Report on a raw folder without importing it.

				  import <mods-dir> <profile> <mc-version> [loader]
				      Adopt an existing mods folder as a profile. Copies, never moves.
				  list
				  migrate <profile> <target-mc-version> [--apply] [--include-likely]
				      Without --apply this only reports. --include-likely also accepts
				      mods matched by name rather than by hash.
				  snapshots <profile>
				  rollback <profile> <snapshot-id>
				  sync <from-profile> <to-profile> [--overwrite]
				      Copy mod configs between profiles.
				  prune
				      Delete stored files nothing references any more.

				  search <profile> <query>
				      Find mods that fit a profile's Minecraft version and loader.
				  add <profile> <project-id-or-slug>
				      Install a mod and everything it requires.
				  remove <profile> <file-name>
				  toggle <profile> <file-name> <on|off>

				  java
				      List Java installations Loadout can find.
				  install <mc-version> [loader-version]
				      Download Minecraft, Fabric and assets. Shared between profiles.
				  run <profile> [username]
				      Launch a profile. Offline session unless an account is configured.
				""");
	}

	private static void need(List<String> args, int count, String form) {
		if (args.size() < count) {
			System.err.println("usage: loadout " + form);
			System.exit(2);
		}
	}

	private static String loaderOr(List<String> args, int index) {
		return args.size() > index ? args.get(index) : "fabric";
	}

	private static MigrationPlan planFor(List<ModJar> mods, String target, String loader) throws Exception {
		System.out.printf("Checking %d mods against Minecraft %s (%s)...%n%n", mods.size(), target, loader);
		return new MigrationPlanner(new ModrinthClient()).plan(mods, target, loader);
	}

	private static void scan(Path modsDir) throws Exception {
		List<ModJar> mods = ModScanner.scan(modsDir);
		if (mods.isEmpty()) {
			System.out.println("No mods found in " + modsDir);
			return;
		}

		long enabled = mods.stream().filter(ModJar::enabled).count();
		System.out.printf("%d mods in %s (%d enabled, %d disabled)%n%n",
				mods.size(), modsDir, enabled, mods.size() - enabled);

		for (ModJar mod : mods) {
			System.out.printf("  %-3s %-38s %-16s %s%n",
					mod.enabled() ? "" : "off",
					truncate(mod.displayName(), 38),
					truncate(mod.version() == null ? "-" : mod.version(), 16),
					mod.isFabricMod() ? "" : "(no fabric.mod.json)");
		}
	}

	private static void list(LoadoutHome home) throws Exception {
		List<String> names = home.profileNames();
		if (names.isEmpty()) {
			System.out.println("No profiles yet. Try: loadout import <mods-dir> <name> <mc-version>");
			return;
		}

		System.out.printf("%-24s %-12s %-10s %s%n", "PROFILE", "MINECRAFT", "LOADER", "MODS");
		for (String name : names) {
			Profile profile = home.loadProfile(name);
			System.out.printf("%-24s %-12s %-10s %d (%d on)%n",
					truncate(name, 24), profile.minecraftVersion(), profile.loader(),
					profile.mods().size(), profile.enabledMods().size());
		}

		System.out.println("\nStore: " + home.store().root());
	}

	private static void migrate(LoadoutHome home, ProfileManager manager, String name, String target,
			boolean apply, boolean includeLikely) throws Exception {
		if (!home.exists(name)) {
			System.err.println("No profile called '" + name + "'. Try: loadout list");
			System.exit(1);
		}

		Profile profile = home.loadProfile(name);
		List<ModJar> mods = ModScanner.scan(home.modsDir(name));
		MigrationPlan plan = planFor(mods, target, profile.loader());
		report(plan, target);

		if (!apply) {
			System.out.println("Nothing changed. Add --apply to carry this out.");
			return;
		}

		if (!plan.isClean()) {
			System.out.printf("%n%d mods have nowhere to go. Migrating anyway would leave them "
					+ "on the old version, which usually means the game won't start.%n", plan.blockers().size());
			System.out.println("Resolve those first, or remove them from the profile.");
			System.exit(1);
		}

		System.out.println();
		ProfileManager.MigrationResult result = manager.apply(profile, plan, includeLikely,
				(file, bytes) -> System.out.printf("  downloading %s (%.1f MB)%n", file, bytes / 1_048_576.0));

		System.out.printf("%nMigrated '%s' to %s - %d mods changed.%n", name, target, result.modsChanged());
		System.out.printf("Snapshot %s taken first; undo with: loadout rollback %s %s%n",
				result.snapshotId(), name, result.snapshotId());
	}

	private static void snapshots(LoadoutHome home, String name) throws Exception {
		List<Snapshot> snapshots = home.snapshots(name);
		if (snapshots.isEmpty()) {
			System.out.println("No snapshots for '" + name + "' yet.");
			return;
		}

		System.out.printf("%-22s %-14s %s%n", "ID", "MODS", "REASON");
		for (Snapshot snapshot : snapshots) {
			System.out.printf("%-22s %-14d %s%n",
					snapshot.id(), snapshot.profile().mods().size(), snapshot.reason());
		}
	}

	private static void search(LoadoutHome home, String profileName, String query) throws Exception {
		if (!home.exists(profileName)) {
			System.err.println("No profile called '" + profileName + "'. Try: loadout list");
			System.exit(1);
		}

		Profile profile = home.loadProfile(profileName);
		List<SearchResult> results = new ModBrowser()
				.search(query, profile.minecraftVersion(), profile.loader(), "relevance", 15, 0);

		if (results.isEmpty()) {
			System.out.printf("Nothing found for '%s' on %s / %s.%n",
					query, profile.minecraftVersion(), profile.loader());
			return;
		}

		System.out.printf("Compatible with %s (%s)%n%n", profile.minecraftVersion(), profile.loader());
		System.out.printf("%-26s %-8s %-30s %s%n", "SLUG", "DOWNLOADS", "NAME", "DESCRIPTION");
		for (SearchResult result : results) {
			System.out.printf("%-26s %-8s %-30s %s%n",
					truncate(result.slug(), 26),
					result.downloadsShort(),
					truncate(result.title(), 30),
					truncate(result.description(), 48));
		}

		System.out.printf("%nInstall with: loadout add %s <slug>%n", profileName);
	}

	private static void add(LoadoutHome home, String profileName, String projectId) throws Exception {
		if (!home.exists(profileName)) {
			System.err.println("No profile called '" + profileName + "'. Try: loadout list");
			System.exit(1);
		}

		ModInstaller installer = new ModInstaller(home);
		ModInstaller.Result result = installer.install(profileName, projectId,
				(file, bytes) -> System.out.printf("  downloading %s (%.1f MB)%n", file, bytes / 1_048_576.0));

		if (!result.alreadyPresent().isEmpty()) {
			System.out.println("Already installed: " + String.join(", ", result.alreadyPresent()));
		}
		if (!result.unavailable().isEmpty()) {
			System.out.println("No build for this version: " + String.join(", ", result.unavailable()));
		}

		if (!result.upgraded().isEmpty()) {
			System.out.println("Upgraded in place:");
			result.upgraded().forEach(line -> System.out.println("  " + line));
		}

		if (result.changedAnything() || !result.upgraded().isEmpty()) {
			System.out.printf("%nInstalled %d file(s) into '%s':%n", result.installed().size(), profileName);
			result.installed().forEach(name -> System.out.println("  " + name));
		} else {
			System.out.println("Nothing to do.");
		}
	}

	private static void javas() {
		List<JavaLocator.JavaInstall> installs = JavaLocator.findAll();
		if (installs.isEmpty()) {
			System.out.println("No Java installations found.");
			return;
		}

		System.out.printf("%-8s %s%n", "VERSION", "PATH");
		for (JavaLocator.JavaInstall install : installs) {
			System.out.printf("%-8d %s%n", install.majorVersion(), install.executable());
		}
	}

	private static void install(LoadoutHome home, String versionId, String loaderVersion) throws Exception {
		GameInstaller installer = new GameInstaller(home.minecraftRoot());

		System.out.println("Fetching metadata for " + versionId + "...");
		JsonObject versionJson = installer.versionJson(versionId);
		JsonObject fabric = installer.fabricProfile(versionId, loaderVersion);

		int required = JavaLocator.requiredMajor(versionJson);
		System.out.printf("Minecraft %s needs Java %d.%n", versionId, required);

		installer.install(versionId, versionJson, fabric, (stage, done, total) -> {
			if (done == total) {
				System.out.printf("  %s: %d/%d%n", stage, done, total);
			} else if (total > 50 && done % 200 == 0) {
				System.out.printf("  %s: %d/%d%n", stage, done, total);
			}
		});

		System.out.println("Installed to " + home.minecraftRoot());
	}

	private static void run(LoadoutHome home, String name, String username) throws Exception {
		if (!home.exists(name)) {
			System.err.println("No profile called '" + name + "'. Try: loadout list");
			System.exit(1);
		}

		Profile profile = home.loadProfile(name);
		GameInstaller installer = new GameInstaller(home.minecraftRoot());

		JsonObject versionJson = installer.versionJson(profile.minecraftVersion());
		JsonObject fabric = installer.fabricProfile(profile.minecraftVersion(), null);

		int required = JavaLocator.requiredMajor(versionJson);
		JavaLocator.JavaInstall javaInstall = JavaLocator.bestFor(required).orElse(null);
		if (javaInstall == null) {
			System.err.printf("Minecraft %s needs Java %d and none was found.%n",
					profile.minecraftVersion(), required);
			System.err.println("Installed Javas:");
			JavaLocator.findAll().forEach(i ->
					System.err.printf("  %d  %s%n", i.majorVersion(), i.executable()));
			System.exit(1);
		}

		System.out.printf("Installing %s if needed...%n", profile.minecraftVersion());
		installer.install(profile.minecraftVersion(), versionJson, fabric, (stage, done, total) -> {
			if (done == total) {
				System.out.printf("  %s: %d%n", stage, total);
			}
		});

		// Offline play requires an account that has completed a Microsoft sign-in on this
		// machine. Without that check a launcher will start the game for any name typed
		// at it, which is a licence bypass rather than offline support.
		AccountStore accounts = new AccountStore(home.root());
		StoredAccount verified = accounts.byUsername(username)
				.filter(StoredAccount::isVerified)
				.or(() -> {
					try {
						return accounts.primary();
					} catch (java.io.IOException e) {
						return java.util.Optional.empty();
					}
				})
				.orElse(null);

		if (verified == null) {
			System.err.println("No signed-in account. Loadout needs a Microsoft account to have");
			System.err.println("authenticated at least once before it will launch, including offline.");
			System.err.println();
			System.err.println("  loadout login        (not yet available - pending Azure app approval)");
            System.exit(1);
            return;
		}

		LaunchBuilder.Account account = LaunchBuilder.Account.offlineFor(verified);

		LogRedactor redactor = new LogRedactor();
		redactor.addSecret(account.accessToken());

		List<String> command = LaunchBuilder.build(
				javaInstall.executable().toString(), versionJson, fabric, installer,
				profile.minecraftVersion(), home.profileDir(name), account,
				List.of("-Xmx4G"));

		System.out.printf("%nLaunching '%s' on Java %d as %s (offline)%n",
				name, javaInstall.majorVersion(), account.username());
		System.out.println("Log: " + home.logFile(name));
		System.out.println();

		try (GameSession session = GameSession.start(
				command, home.profileDir(name), home.logFile(name), redactor, System.out::println)) {
			int code = session.awaitExit();
			System.out.printf("%nGame exited with code %d.%n", code);
		}
	}

	private static void prune(LoadoutHome home) throws Exception {
		long freed = home.store().prune(home.referencedHashes());
		System.out.printf("Reclaimed %.1f MB.%n", freed / 1_048_576.0);
	}

	private static void report(MigrationPlan plan, String target) {
		section("Would update", plan.changes(), entry ->
				String.format("%-36s %s -> %s",
						truncate(entry.jar().displayName(), 36),
						truncate(nullSafe(entry.jar().version()), 14),
						entry.target().versionNumber()));

		section("Already fine", plan.withStatus(MigrationPlan.Status.ALREADY_SUPPORTED), entry ->
				truncate(entry.jar().displayName(), 36));

		section("Likely matches - confirm these", plan.needsConfirming(), entry ->
				String.format("%-36s %s -> %s",
						truncate(entry.jar().displayName(), 36),
						truncate(nullSafe(entry.jar().version()), 14),
						entry.target().versionNumber()));

		// target is null when a mod was found by name but has no build for the target.
		section("No build for " + target, plan.withStatus(MigrationPlan.Status.NO_BUILD), entry ->
				String.format("%-36s %s",
						truncate(entry.jar().displayName(), 36),
						entry.target() == null ? "" : entry.target().projectUrl()));

		section("Not on Modrinth", plan.withStatus(MigrationPlan.Status.UNKNOWN), entry ->
				String.format("%-36s %s", truncate(entry.jar().displayName(), 36), entry.jar().fileName()));

		section("Disabled, ignored", plan.withStatus(MigrationPlan.Status.DISABLED), entry ->
				truncate(entry.jar().displayName(), 36));

		System.out.println();
		if (plan.isClean()) {
			System.out.printf("This profile can move to %s. %d mods would be updated.%n",
					target, plan.changes().size() + plan.needsConfirming().size());
		} else {
			System.out.printf("%d mods would block the move to %s.%n", plan.blockers().size(), target);
		}
	}

	private interface Format {
		String apply(MigrationPlan.Entry entry);
	}

	private static void section(String title, List<MigrationPlan.Entry> entries, Format format) {
		if (entries.isEmpty()) {
			return;
		}

		System.out.printf("%s (%d)%n", title, entries.size());
		for (MigrationPlan.Entry entry : entries) {
			System.out.println("  " + format.apply(entry));
		}
		System.out.println();
	}

	private static String nullSafe(String value) {
		return value == null ? "-" : value;
	}

	private static String truncate(String value, int width) {
		if (value == null) {
			return "-";
		}
		return value.length() <= width ? value : value.substring(0, width - 2) + "..";
	}
}
