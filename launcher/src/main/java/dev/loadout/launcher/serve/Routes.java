package dev.loadout.launcher.serve;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.loadout.core.LoadoutHome;
import dev.loadout.core.MigrationPlan;
import dev.loadout.core.MigrationPlanner;
import dev.loadout.core.ModJar;
import dev.loadout.core.ModScanner;
import dev.loadout.core.ModrinthClient;
import dev.loadout.core.Profile;
import dev.loadout.core.ProfileManager;
import dev.loadout.core.Settings;
import dev.loadout.core.Snapshot;
import dev.loadout.core.auth.AccountStore;
import dev.loadout.core.auth.StoredAccount;
import dev.loadout.core.browse.ModInstaller;
import dev.loadout.core.launch.GameInstaller;
import dev.loadout.core.launch.GameSession;
import dev.loadout.core.launch.JavaLocator;
import dev.loadout.core.launch.LaunchBuilder;
import dev.loadout.core.launch.LogRedactor;
import dev.loadout.core.launch.VersionCatalog;
import dev.loadout.core.source.ModSource;
import dev.loadout.core.source.RemoteMod;
import dev.loadout.core.source.SourceId;
import dev.loadout.core.source.SourceRegistry;
import java.io.IOException;
import java.util.List;

/**
 * The API's actual behaviour, one method per endpoint.
 *
 * <p>Separate from {@link ApiServer} so the routing table and the access control stay
 * readable on their own, and so nothing here has to know about HTTP beyond the status
 * codes it throws.
 *
 * <p>Nothing in here reimplements logic. Every method is a translation between JSON and
 * the same core calls the CLI makes, which is the point of having a core at all -- two
 * front ends that disagree about what "install" means would be worse than one.
 */
final class Routes {
	private final LoadoutHome home;
	private final ProfileManager profiles;
	private final Jobs jobs;

	Routes(LoadoutHome home, Jobs jobs) {
		this.home = home;
		this.profiles = new ProfileManager(home);
		this.jobs = jobs;
	}

	// -- meta ------------------------------------------------------------------------

	JsonObject health() {
		JsonObject json = Json.object();
		json.addProperty("ok", true);
		json.addProperty("version", "0.1.0");
		json.addProperty("home", this.home.root().toString());
		return json;
	}

	// -- sources and settings --------------------------------------------------------

	/**
	 * Lists sources and, when asked, checks each one really works.
	 *
	 * <p>Verification is opt-in because it makes a network call per source. A sidebar
	 * refreshing on every navigation wants the cheap answer; a settings screen explaining
	 * why half the search results are missing wants the true one.
	 */
	JsonObject sources(ApiServer.Query query) {
		boolean verify = Boolean.parseBoolean(query.get("verify", "false"));
		SourceRegistry registry = this.home.sources();

		JsonArray array = new JsonArray();
		for (ModSource source : registry.all()) {
			JsonObject json = Json.object();
			json.addProperty("id", source.id().key());
			json.addProperty("name", source.id().displayName());
			json.addProperty("configured", source.isAvailable());

			if (!source.isAvailable()) {
				json.addProperty("available", false);
				json.addProperty("reason", source.unavailableReason());
			} else if (verify) {
				ModSource.Verification check = source.verify();
				json.addProperty("available", check.succeeded());
				if (!check.succeeded()) {
					json.addProperty("reason", check.detail());
				}
			} else {
				// Unverified: a key is present, which is all that was asked.
				json.addProperty("available", true);
			}
			array.add(json);
		}

		JsonObject result = Json.object();
		result.add("sources", array);
		result.addProperty("verified", verify);
		return result;
	}

	/**
	 * Stores a CurseForge key.
	 *
	 * <p>The key is never echoed back, here or anywhere else. A UI that displays a
	 * credential it did not need to display is a credential in a screenshot.
	 */
	JsonObject setCurseForgeKey(JsonObject body) throws IOException {
		String key = Json.optionalString(body, "key", null);

		// Clearing is always allowed; there is nothing to check about an absent key.
		if (key == null || key.isBlank()) {
			Settings settings = this.home.settings();
			settings.setCurseForgeApiKey(null);
			settings.save(this.home.root());

			JsonObject result = Json.object();
			result.addProperty("configured", false);
			return result;
		}

		ModSource.Verification check =
				new SourceRegistry(key).get(SourceId.CURSEFORGE).verify();
		if (!check.succeeded()) {
			// 400 rather than 401: the caller's own credentials were fine, the value they
			// supplied was not.
			throw new ApiException(400, "That key was not accepted: " + check.detail());
		}

		Settings settings = this.home.settings();
		settings.setCurseForgeApiKey(key);
		settings.save(this.home.root());

		JsonObject result = Json.object();
		result.addProperty("configured", true);
		return result;
	}

	// -- profiles --------------------------------------------------------------------

	JsonObject profiles() throws IOException {
		JsonArray array = new JsonArray();
		for (String name : this.home.profileNames()) {
			Profile profile = this.home.loadProfile(name);

			JsonObject json = Json.object();
			json.addProperty("name", profile.name());
			json.addProperty("minecraftVersion", profile.minecraftVersion());
			json.addProperty("loader", profile.loader());
			json.addProperty("modCount", profile.mods().size());
			json.addProperty("enabledCount", profile.enabledMods().size());
			array.add(json);
		}

		JsonObject result = Json.object();
		result.add("profiles", array);
		return result;
	}

	JsonObject profile(String name) throws IOException {
		Profile profile = require(name);

		JsonObject json = Json.object();
		json.addProperty("name", profile.name());
		json.addProperty("minecraftVersion", profile.minecraftVersion());
		json.addProperty("loader", profile.loader());
		json.addProperty("directory", this.home.profileDir(name).toString());
		json.add("mods", Json.arrayOf(profile.mods(), Routes::entryJson));
		return json;
	}

	private static JsonObject entryJson(Profile.Entry entry) {
		JsonObject json = Json.object();
		json.addProperty("fileName", entry.fileName());
		json.addProperty("enabled", entry.enabled());
		json.addProperty("sha512", entry.sha512());

		// Optional because an imported jar may match nothing on any registry -- a local
		// build, or a mod that was only ever posted on a forum.
		if (entry.projectId() != null) {
			json.addProperty("projectId", entry.projectId());
		}
		if (entry.versionNumber() != null) {
			json.addProperty("versionNumber", entry.versionNumber());
		}
		if (entry.modId() != null) {
			json.addProperty("modId", entry.modId());
		}
		if (entry.source() != null) {
			json.addProperty("source", entry.source());
		}
		return json;
	}

	JsonObject createProfile(JsonObject body) throws IOException {
		String name = Json.requireString(body, "name");
		String minecraftVersion = Json.requireString(body, "minecraftVersion");
		String loader = Json.optionalString(body, "loader", "fabric");

		try {
			LoadoutHome.requireValidName(name);
		} catch (IllegalArgumentException e) {
			throw new ApiException(400, e.getMessage());
		}

		if (this.home.exists(name)) {
			throw new ApiException(409, "A profile called '" + name + "' already exists");
		}

		Profile profile = new Profile(name, minecraftVersion, loader, List.of());
		this.home.saveProfile(profile);
		this.profiles.materialise(profile);

		return profile(name);
	}

	JsonObject deleteProfile(String name) throws IOException {
		boolean removed;
		try {
			removed = this.home.deleteProfile(name);
		} catch (IllegalArgumentException e) {
			throw new ApiException(400, e.getMessage());
		}

		if (!removed) {
			throw ApiException.notFound("No profile called '" + name + "'");
		}

		JsonObject result = Json.object();
		result.addProperty("deleted", true);
		// Worth saying explicitly: the deletion is reversible until someone prunes, and a
		// UI that does not mention that will get support questions instead of rollbacks.
		result.addProperty("snapshotsKept", this.home.snapshots(name).size());
		return result;
	}

	// -- mods ------------------------------------------------------------------------

	JsonObject installMod(String profileName, JsonObject body) throws IOException {
		require(profileName);

		String id = Json.requireString(body, "id");
		String sourceKey = Json.optionalString(body, "source", SourceId.MODRINTH.key());

		SourceId source = SourceId.fromKey(sourceKey);
		if (source == null) {
			throw new ApiException(400, "Unknown source: " + sourceKey);
		}

		String versionId = Json.optionalString(body, "versionId", null);

		String jobId = this.jobs.submit("install", profileName + " / " + id, reporter -> {
			ModInstaller.Result result = new ModInstaller(this.home).install(
					profileName, source, id, versionId,
					(fileName, bytes) -> reporter.log("Downloading " + fileName));

			JsonObject json = Json.object();
			json.add("installed", Json.stringsOf(result.installed()));
			json.add("upgraded", Json.stringsOf(result.upgraded()));
			json.add("alreadyPresent", Json.stringsOf(result.alreadyPresent()));
			json.add("unavailable", Json.stringsOf(result.unavailable()));
			json.add("blocked", Json.stringsOf(result.blocked()));
			json.addProperty("changed", result.changedAnything());
			return json;
		});

		return jobRef(jobId);
	}

	JsonObject removeMod(String profileName, String fileName) throws IOException {
		require(profileName);

		if (!new ModInstaller(this.home).remove(profileName, fileName)) {
			throw ApiException.notFound("No mod called '" + fileName + "' in " + profileName);
		}

		JsonObject result = Json.object();
		result.addProperty("removed", true);
		return result;
	}

	JsonObject toggleMod(String profileName, String fileName, JsonObject body) throws IOException {
		require(profileName);

		boolean enabled = Json.optionalBoolean(body, "enabled", true);
		if (!new ModInstaller(this.home).setEnabled(profileName, fileName, enabled)) {
			throw ApiException.notFound("No mod called '" + fileName + "' in " + profileName);
		}

		JsonObject result = Json.object();
		result.addProperty("fileName", fileName);
		result.addProperty("enabled", enabled);
		return result;
	}

	/**
	 * Artwork for the mods in a profile, keyed by file name.
	 *
	 * <p>Separate from the profile itself because it needs the network and the profile does
	 * not. A mod list that cannot render until two registries have answered is a mod list
	 * that looks broken when one of them is slow -- so the names and versions arrive
	 * immediately and the pictures fill in behind them.
	 *
	 * <p>Grouped by source before asking, since ids only mean anything to the registry that
	 * issued them, and each source resolves its whole set in one request.
	 */
	JsonObject modIcons(String profileName) throws IOException {
		Profile profile = require(profileName);
		SourceRegistry registry = this.home.sources();

		java.util.Map<SourceId, java.util.List<String>> wanted = new java.util.LinkedHashMap<>();
		for (Profile.Entry entry : profile.mods()) {
			SourceId source = entry.sourceId();
			if (source != null && entry.projectId() != null) {
				wanted.computeIfAbsent(source, key -> new java.util.ArrayList<>())
						.add(entry.projectId());
			}
		}

		java.util.Map<SourceId, java.util.Map<String, String>> resolved = new java.util.HashMap<>();
		for (var group : wanted.entrySet()) {
			ModSource source = registry.get(group.getKey());
			if (source == null || !source.isAvailable()) {
				continue;
			}
			try {
				resolved.put(group.getKey(), source.icons(group.getValue()));
			} catch (IOException | InterruptedException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				// One registry being unreachable should still let the other's artwork show.
			}
		}

		JsonObject icons = Json.object();
		for (Profile.Entry entry : profile.mods()) {
			SourceId source = entry.sourceId();
			if (source == null || entry.projectId() == null) {
				continue;
			}
			String url = resolved.getOrDefault(source, java.util.Map.of()).get(entry.projectId());
			if (url != null) {
				icons.addProperty(entry.fileName(), url);
			}
		}

		JsonObject result = Json.object();
		result.add("icons", icons);
		return result;
	}

	// -- snapshots -------------------------------------------------------------------

	JsonObject snapshots(String profileName) throws IOException {
		require(profileName);

		JsonArray array = Json.arrayOf(this.home.snapshots(profileName), snapshot -> {
			JsonObject json = Json.object();
			json.addProperty("id", snapshot.id());
			json.addProperty("takenAt", snapshot.takenAt());
			json.addProperty("reason", snapshot.reason());
			json.addProperty("modCount", snapshot.profile().mods().size());
			json.addProperty("minecraftVersion", snapshot.profile().minecraftVersion());
			return json;
		});

		JsonObject result = Json.object();
		result.add("snapshots", array);
		return result;
	}

	JsonObject rollback(String profileName, JsonObject body) throws IOException {
		require(profileName);
		String snapshotId = Json.requireString(body, "snapshotId");

		Profile restored = this.profiles.rollback(profileName, snapshotId);

		JsonObject result = Json.object();
		result.addProperty("name", restored.name());
		result.addProperty("minecraftVersion", restored.minecraftVersion());
		result.addProperty("modCount", restored.mods().size());
		return result;
	}

	// -- migration -------------------------------------------------------------------

	/**
	 * Plans a migration, and carries it out when asked.
	 *
	 * <p>Planning is the slow part -- it is a hash lookup per mod against Modrinth -- so
	 * it runs as a job even when {@code apply} is false and nothing will be written.
	 */
	JsonObject migrate(String profileName, JsonObject body) throws IOException {
		Profile profile = require(profileName);

		String target = Json.requireString(body, "target");
		boolean apply = Json.optionalBoolean(body, "apply", false);
		boolean includeLikely = Json.optionalBoolean(body, "includeLikely", false);

		String jobId = this.jobs.submit("migrate", profileName + " to " + target, reporter -> {
			List<ModJar> mods = ModScanner.scan(this.home.modsDir(profileName));
			reporter.progress("Checking " + mods.size() + (mods.size() == 1 ? " mod" : " mods"),
					0, mods.size());

			MigrationPlan plan = new MigrationPlanner(new ModrinthClient())
					.plan(mods, target, profile.loader());

			JsonObject json = Json.object();
			json.add("plan", planJson(plan));

			if (!apply) {
				json.addProperty("applied", false);
				return json;
			}

			if (!reporter.shouldContinue()) {
				throw new InterruptedException();
			}

			reporter.progress("Downloading", 0, plan.changes().size());
			ProfileManager.MigrationResult result = this.profiles.apply(
					profile, plan, includeLikely,
					(fileName, bytes) -> reporter.log("Downloading " + fileName));

			json.addProperty("applied", true);
			json.addProperty("snapshotId", result.snapshotId());
			json.addProperty("modsChanged", result.modsChanged());
			json.addProperty("blockersRemaining", result.blockersRemaining());
			return json;
		});

		return jobRef(jobId);
	}

	private static JsonObject planJson(MigrationPlan plan) {
		JsonObject json = Json.object();
		json.addProperty("targetGameVersion", plan.targetGameVersion());
		json.addProperty("loader", plan.loader());
		json.addProperty("clean", plan.isClean());

		json.add("entries", Json.arrayOf(plan.entries(), entry -> {
			JsonObject item = Json.object();
			item.addProperty("fileName", entry.jar().fileName());
			item.addProperty("name", entry.jar().displayName());
			item.addProperty("status", entry.status().name().toLowerCase());
			item.addProperty("blocks", entry.blocks());
			if (entry.target() != null) {
				item.addProperty("targetFileName", entry.target().fileName());
				item.addProperty("targetVersion", entry.target().versionNumber());
			}
			return item;
		}));
		return json;
	}

	// -- launching -------------------------------------------------------------------

	/**
	 * Downloads whatever is missing and starts the game.
	 *
	 * <p>The job stays running for as long as the session does, so its log is the game's
	 * log and finishing means the game exited.
	 */
	JsonObject launch(String profileName, JsonObject body) throws IOException {
		Profile profile = require(profileName);
		String username = Json.optionalString(body, "username", null);

		String jobId = this.jobs.submit("launch", profileName, reporter -> {
			GameInstaller installer = new GameInstaller(this.home.minecraftRoot());

			reporter.progress("Fetching metadata", 0, 1);
			com.google.gson.JsonObject versionJson = installer.versionJson(profile.minecraftVersion());
			com.google.gson.JsonObject fabric =
					installer.fabricProfile(profile.minecraftVersion(), null);

			int required = JavaLocator.requiredMajor(versionJson);
			JavaLocator.JavaInstall java = JavaLocator.bestFor(required).orElseThrow(() ->
					new IllegalStateException("Minecraft " + profile.minecraftVersion()
							+ " needs Java " + required + " and none was found"));

			installer.install(profile.minecraftVersion(), versionJson, fabric,
					(stage, done, total) -> reporter.progress(stage, done, total));

			// Offline play still requires an account that has completed a Microsoft sign-in
			// on this machine. Skipping that check would turn the launcher into a way of
			// playing without a licence, which is a different product to the one this is.
			AccountStore accounts = new AccountStore(this.home.root());
			StoredAccount verified = (username == null
					? accounts.primary()
					: accounts.byUsername(username).filter(StoredAccount::isVerified))
					.orElseThrow(() -> new IllegalStateException(
							"No signed-in account. Loadout needs a Microsoft account to have "
									+ "authenticated at least once before it will launch, "
									+ "including offline."));

			LaunchBuilder.Account account = LaunchBuilder.Account.offlineFor(verified);

			LogRedactor redactor = new LogRedactor();
			redactor.addSecret(account.accessToken());

			List<String> command = LaunchBuilder.build(
					java.executable().toString(), versionJson, fabric, installer,
					profile.minecraftVersion(), this.home.profileDir(profileName), account,
					List.of("-Xmx4G"));

			reporter.progress("Running", 0, 0);
			reporter.log("Launching as " + account.username() + " on Java " + java.majorVersion());

			try (GameSession session = GameSession.start(command, this.home.profileDir(profileName),
					this.home.logFile(profileName), redactor, reporter::log)) {
				int code = session.awaitExit();

				JsonObject json = Json.object();
				json.addProperty("exitCode", code);
				json.addProperty("logFile", this.home.logFile(profileName).toString());
				return json;
			}
		});

		return jobRef(jobId);
	}

	/**
	 * Every Minecraft version Mojang publishes, newest first.
	 *
	 * <p>Served so the interface can offer a list instead of a text box. A mistyped version
	 * produces a profile that matches no mods at all, and that failure surfaces much later
	 * as an empty search rather than as the typo it was.
	 */
	JsonObject minecraftVersions() throws IOException {
		VersionCatalog.Catalog catalog;
		try {
			catalog = new VersionCatalog(new dev.loadout.core.launch.MetaClient()).fetch();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(503, "Interrupted while fetching the version list");
		}

		JsonObject result = Json.object();
		result.add("versions", Json.arrayOf(catalog.versions(), entry -> {
			JsonObject json = Json.object();
			json.addProperty("id", entry.id());
			json.addProperty("type", entry.type());
			json.addProperty("releasedAt", entry.releasedAt());
			return json;
		}));
		if (catalog.latestRelease() != null) {
			result.addProperty("latestRelease", catalog.latestRelease());
		}
		if (catalog.latestSnapshot() != null) {
			result.addProperty("latestSnapshot", catalog.latestSnapshot());
		}
		return result;
	}

	JsonObject javaInstalls() {
		JsonArray array = Json.arrayOf(JavaLocator.findAll(), install -> {
			JsonObject json = Json.object();
			json.addProperty("path", install.executable().toString());
			json.addProperty("majorVersion", install.majorVersion());
			json.addProperty("source", install.source());
			return json;
		});

		JsonObject result = Json.object();
		result.add("installs", array);
		return result;
	}

	/**
	 * Every build of one mod that fits a profile, newest first.
	 *
	 * <p>Scoped to a profile rather than listing everything, because "which versions exist"
	 * is never really the question -- "which can I install here" is, and a list including
	 * builds for the wrong Minecraft version is a list of things that will not work.
	 */
	JsonObject versions(ApiServer.Query query) throws IOException {
		SourceId sourceId = SourceId.fromKey(query.require("source"));
		if (sourceId == null) {
			throw new ApiException(400, "Unknown source: " + query.require("source"));
		}

		String modId = query.require("id");
		Profile profile = require(query.require("profile"));

		ModSource source = this.home.sources().get(sourceId);
		if (source == null || !source.isAvailable()) {
			throw new ApiException(400, sourceId.displayName() + " is not available");
		}

		java.util.List<dev.loadout.core.source.RemoteFile> files;
		try {
			files = source.versions(modId, profile.minecraftVersion(), profile.loader());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(503, "Interrupted while listing versions");
		}

		JsonObject result = Json.object();
		result.add("versions", Json.arrayOf(files, file -> {
			JsonObject json = Json.object();
			json.addProperty("versionId", file.versionId());
			json.addProperty("versionNumber", file.versionNumber());
			json.addProperty("fileName", file.fileName());
			json.addProperty("fileSize", file.fileSize());
			json.addProperty("downloadable", file.isDownloadable());
			return json;
		}));
		return result;
	}

	// -- search ----------------------------------------------------------------------

	/**
	 * Searches every available source at once.
	 *
	 * <p>Takes a profile rather than a version and loader where possible, because the
	 * question a user is really asking is "what can I install *here*" -- and a listing
	 * that shows mods which will not run in this profile is worse than a shorter one.
	 */
	JsonObject search(ApiServer.Query query) throws IOException {
		String text = query.get("q", "");
		String profileName = query.get("profile", null);

		String gameVersion = query.get("gameVersion", null);
		String loader = query.get("loader", null);

		if (profileName != null) {
			Profile profile = require(profileName);
			gameVersion = profile.minecraftVersion();
			loader = profile.loader();
		}

		ModSource.SortOrder sort = sortOrder(query.get("sort", null), text);
		int limit = Math.min(query.getInt("limit", 30), 100);

		String sourceKey = query.get("source", null);
		SourceId only = null;
		if (sourceKey != null && !sourceKey.equalsIgnoreCase("all")) {
			only = SourceId.fromKey(sourceKey);
			if (only == null) {
				throw new ApiException(400, "Unknown source: " + sourceKey);
			}
		}

		SourceRegistry.Merged merged =
				this.home.sources().search(text, gameVersion, loader, sort, limit, only);

		JsonObject result = Json.object();
		result.add("results", Json.arrayOf(merged.results(), Routes::remoteModJson));
		result.add("notes", Json.stringsOf(merged.notes()));
		return result;
	}

	private static ModSource.SortOrder sortOrder(String requested, String text) {
		if (requested != null) {
			try {
				return ModSource.SortOrder.valueOf(requested.toUpperCase());
			} catch (IllegalArgumentException e) {
				throw new ApiException(400, "Unknown sort order: " + requested);
			}
		}
		// Relevance is meaningless without a query, so an empty search is really "show me
		// what is popular".
		return text.isBlank() ? ModSource.SortOrder.DOWNLOADS : ModSource.SortOrder.RELEVANCE;
	}

	private static JsonObject remoteModJson(RemoteMod mod) {
		JsonObject json = Json.object();
		json.addProperty("source", mod.source().key());
		json.addProperty("id", mod.id());
		json.addProperty("title", mod.title());
		json.addProperty("downloads", mod.downloads());
		json.addProperty("downloadsShort", mod.downloadsShort());
		json.addProperty("webUrl", mod.webUrl());

		if (mod.slug() != null) {
			json.addProperty("slug", mod.slug());
		}
		if (mod.description() != null) {
			json.addProperty("description", mod.description());
		}
		if (mod.author() != null) {
			json.addProperty("author", mod.author());
		}
		if (mod.iconUrl() != null) {
			json.addProperty("iconUrl", mod.iconUrl());
		}
		json.add("categories", Json.stringsOf(mod.categories()));
		return json;
	}

	// -- jobs ------------------------------------------------------------------------

	JsonObject jobs() {
		JsonObject result = Json.object();
		result.add("jobs", Json.arrayOf(this.jobs.all(), job -> job.toJson(false)));
		return result;
	}

	JsonObject job(String id) {
		return this.jobs.get(id)
				.map(job -> job.toJson(true))
				.orElseThrow(() -> ApiException.notFound("No job called '" + id + "'"));
	}

	JsonObject cancelJob(String id) {
		if (this.jobs.get(id).isEmpty()) {
			throw ApiException.notFound("No job called '" + id + "'");
		}

		JsonObject result = Json.object();
		// False rather than an error when the job already finished: cancelling something
		// that just completed is a race, not a mistake by the caller.
		result.addProperty("cancelling", this.jobs.cancel(id));
		return result;
	}

	private static JsonObject jobRef(String id) {
		JsonObject json = Json.object();
		json.addProperty("jobId", id);
		return json;
	}

	// -- shared ----------------------------------------------------------------------

	private Profile require(String name) throws IOException {
		try {
			if (!this.home.exists(name)) {
				throw ApiException.notFound("No profile called '" + name + "'");
			}
			return this.home.loadProfile(name);
		} catch (IllegalArgumentException e) {
			// An invalid name cannot name a profile, so this is a bad request rather than
			// a missing one.
			throw new ApiException(400, e.getMessage());
		}
	}
}
