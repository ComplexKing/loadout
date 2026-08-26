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
import dev.loadout.core.browse.ModInstaller;
import dev.loadout.core.Settings;
import dev.loadout.core.source.ModSource;
import dev.loadout.core.source.RemoteMod;
import dev.loadout.core.source.SourceId;
import dev.loadout.core.source.SourceRegistry;
import dev.loadout.launcher.ui.MainWindow;
import dev.loadout.launcher.ui.Theme;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
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
			case "new" -> {
				need(rest, 2, "new <profile> <mc-version> [loader]");
				newProfile(home, manager, rest.get(0), rest.get(1), loaderOr(rest, 2));
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
			case "sources" -> sources(home);
			case "key" -> {
				need(rest, 1, "key curseforge [<api-key>]");
				setKey(home, rest.get(0), rest.size() > 1 ? rest.get(1) : null);
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
			case "serve" -> {
				// Blocks until killed. Everything it needs to announce -- port and token --
				// goes to stdout as one JSON line for whichever process spawned it.
				dev.loadout.launcher.serve.ApiServer.start(home, portFrom(rest));
			}
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

				  new <profile> <mc-version> [loader]
				      Create an empty profile.
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
				  add <profile> <source>:<id>
				      Install a mod and everything it requires. Source defaults to
				      modrinth, so "loadout add main sodium" works.
				  sources
				      Show which mod sources are usable.
				  key curseforge [<api-key>]
				      Store a CurseForge API key from console.curseforge.com. Omit the key
				      to be prompted for it, which keeps it out of your shell history and
				      stops the shell expanding the $ sections it contains.
				  remove <profile> <file-name>
				  toggle <profile> <file-name> <on|off>

				  java
				      List Java installations Loadout can find.
				  install <mc-version> [loader-version]
				      Download Minecraft, Fabric and assets. Shared between profiles.
				  run <profile> [username]
				      Launch a profile. Offline session unless an account is configured.

				  serve [--port <n>]
				      Expose the API on 127.0.0.1 for a desktop UI to drive. Prints its
				      port and access token as JSON, then runs until stopped.
				""");
	}

	/** Reads --port, defaulting to 0 so the OS picks a free one. */
	private static int portFrom(List<String> args) {
		int at = args.indexOf("--port");
		if (at < 0) {
			return 0;
		}
		if (at + 1 >= args.size()) {
			System.err.println("usage: loadout serve [--port <n>]");
			System.exit(2);
		}
		try {
			return Integer.parseInt(args.get(at + 1));
		} catch (NumberFormatException e) {
			System.err.println("Not a port number: " + args.get(at + 1));
			System.exit(2);
			return 0;
		}
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

	private static void newProfile(LoadoutHome home, ProfileManager manager, String name,
			String version, String loader) throws Exception {
		try {
			LoadoutHome.requireValidName(name);
		} catch (IllegalArgumentException e) {
			System.err.println(e.getMessage());
			System.exit(2);
		}

		if (home.exists(name)) {
			System.err.println("A profile called '" + name + "' already exists.");
			System.exit(1);
		}

		Profile profile = new Profile(name, version, loader, List.of());
		home.saveProfile(profile);
		manager.materialise(profile);

		System.out.printf("Created '%s' (Minecraft %s, %s)%n", name, version, loader);
		System.out.println("  " + home.modsDir(name));
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
		SourceRegistry.Merged merged = home.sources().search(query, profile.minecraftVersion(),
				profile.loader(), ModSource.SortOrder.RELEVANCE, 15);

		if (merged.results().isEmpty()) {
			System.out.printf("Nothing found for '%s' on %s / %s.%n",
					query, profile.minecraftVersion(), profile.loader());
		} else {
			System.out.printf("Compatible with %s (%s)%n%n", profile.minecraftVersion(), profile.loader());
			System.out.printf("%-13s %-24s %-8s %s%n", "SOURCE", "ID", "DOWNLOADS", "NAME");
			for (RemoteMod mod : merged.results()) {
				System.out.printf("%-13s %-24s %-8s %s%n",
						mod.source().key(),
						truncate(mod.slug() == null ? mod.id() : mod.slug(), 24),
						mod.downloadsShort(),
						truncate(mod.title(), 34));
			}
			System.out.printf("%nInstall with: loadout add %s <source>:<id>%n", profileName);
		}

		// Anything switched off or rate limited is worth saying out loud -- otherwise a
		// missing CurseForge key just looks like CurseForge having no matching mods.
		if (!merged.notes().isEmpty()) {
			System.out.println();
			merged.notes().forEach(note -> System.out.println("  note: " + note));
		}
	}

	private static void add(LoadoutHome home, String profileName, String spec) throws Exception {
		if (!home.exists(profileName)) {
			System.err.println("No profile called '" + profileName + "'. Try: loadout list");
			System.exit(1);
		}

		// "curseforge:238222" or just "sodium", which defaults to Modrinth since it needs
		// no key and is what most people mean.
		SourceId source = SourceId.MODRINTH;
		String modId = spec;
		int colon = spec.indexOf(':');
		if (colon > 0) {
			SourceId parsed = SourceId.fromKey(spec.substring(0, colon));
			if (parsed == null) {
				System.err.println("Unknown source '" + spec.substring(0, colon) + "'. Try: loadout sources");
				System.exit(1);
			}
			source = parsed;
			modId = spec.substring(colon + 1);
		}

		ModInstaller.Result result = new ModInstaller(home).install(profileName, source, modId,
				(file, bytes) -> System.out.printf("  downloading %s (%.1f MB)%n", file, bytes / 1_048_576.0));

		if (!result.alreadyPresent().isEmpty()) {
			System.out.println("Already installed: " + String.join(", ", result.alreadyPresent()));
		}
		if (!result.unavailable().isEmpty()) {
			System.out.println("No build for this version: " + String.join(", ", result.unavailable()));
		}
		if (!result.blocked().isEmpty()) {
			System.out.println("Must be downloaded from the website (author has not allowed");
			System.out.println("third-party downloads): " + String.join(", ", result.blocked()));
		}
		if (!result.upgraded().isEmpty()) {
			System.out.println("Upgraded in place:");
			result.upgraded().forEach(line -> System.out.println("  " + line));
		}

		if (!result.installed().isEmpty()) {
			System.out.printf("%nInstalled %d file(s) into '%s':%n", result.installed().size(), profileName);
			result.installed().forEach(name -> System.out.println("  " + name));
		} else if (!result.changedAnything()) {
			System.out.println("Nothing to do.");
		}
	}

	/**
	 * Reports which sources actually work.
	 *
	 * <p>Each configured source is contacted rather than merely inspected. This command
	 * exists to answer "why am I not seeing CurseForge results", and a key that is present
	 * but rejected is the most likely reason -- so reporting "ready" on the strength of a
	 * key existing would hide the exact fault someone ran this to find.
	 */
	/**
	 * Asks for the key without it passing through the shell.
	 *
	 * <p>Three problems disappear at once. The shell cannot expand what it never parses,
	 * so a key full of $ arrives intact. It stays out of shell history. And it stays out
	 * of the process argument list, which on both Windows and Linux is readable by other
	 * processes -- a credential passed as an argument is visible to anything that can list
	 * processes for the duration of the call.
	 */
	private static String promptForKey() {
		java.io.Console console = System.console();
		if (console != null) {
			// Not echoed, so it cannot be read over a shoulder or left in a scrollback.
			char[] entered = console.readPassword("CurseForge API key (input hidden): ");
			return entered == null ? null : new String(entered).trim();
		}

		// No console: being piped to, so read a line and let the caller decide what that
		// means. Supports "type key.txt | loadout key curseforge".
		System.out.println("Reading key from standard input...");
		try (java.util.Scanner scanner = new java.util.Scanner(System.in, StandardCharsets.UTF_8)) {
			return scanner.hasNextLine() ? scanner.nextLine().trim() : null;
		}
	}

	private static void sources(LoadoutHome home) {
		SourceRegistry registry = home.sources();
		System.out.printf("%-14s %-12s %s%n", "SOURCE", "STATUS", "NOTE");

		for (ModSource source : registry.all()) {
			if (!source.isAvailable()) {
				System.out.printf("%-14s %-12s %s%n", source.id().key(), "off", source.unavailableReason());
				continue;
			}

			ModSource.Verification check = source.verify();
			System.out.printf("%-14s %-12s %s%n",
					source.id().key(),
					check.succeeded() ? "ready" : "error",
					check.succeeded() ? "" : check.detail());
		}
	}

	private static void setKey(LoadoutHome home, String sourceKey, String value) throws Exception {
		if (SourceId.fromKey(sourceKey) != SourceId.CURSEFORGE) {
			System.err.println("Only curseforge needs a key. Modrinth works without one.");
			System.exit(1);
		}

		String key = value == null ? promptForKey() : value;
		if (key == null || key.isBlank()) {
			System.err.println("No key entered. Nothing was saved.");
			System.exit(1);
		}

		// A key on the command line is mangled before this program ever sees it. CurseForge
		// issues them in the bcrypt style, so they contain $ -- and an unquoted $2a$10$...
		// is three variable expansions to any shell, which silently eat everything up to
		// the last one. The result is a shorter string that looks like a key and is
		// rejected on every request. Warning is better than guessing: the value may still
		// be legitimate, and refusing it outright would be the same mistake as the shape
		// check this replaced.
		if (value != null && !value.startsWith("$") && value.length() < 50) {
			System.out.println("Note: that does not look like a whole CurseForge key.");
			System.out.println("If you passed it on the command line, your shell may have eaten");
			System.out.println("the $ sections. Run 'loadout key curseforge' with no key to be");
			System.out.println("prompted instead, which avoids the problem entirely.");
			System.out.println();
		}

		System.out.println("Checking the key with CurseForge...");
		ModSource.Verification check = new SourceRegistry(key).get(SourceId.CURSEFORGE).verify();

		if (!check.succeeded()) {
			System.err.println("That key was not accepted: " + check.detail());
			System.err.println();
			System.err.println("Nothing was saved; any key you had before is untouched.");
			System.err.println();
			System.err.println("To enter it without the shell touching it, run:");
			System.err.println("    loadout key curseforge");
			System.exit(1);
		}

		Settings settings = home.settings();
		settings.setCurseForgeApiKey(key);
		settings.save(home.root());
		// Never echo the key back.
		System.out.println("Key accepted and saved to " + Settings.fileIn(home.root()));
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
