package dev.loadout.core.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arguments that put somebody back where they were.
 *
 * <p>The manifest fragment below is copied verbatim from a real version manifest, quick-play
 * entries and all, because the shape is the thing under test. Mojang describes all four
 * quick-play arguments and the game refuses to start when given more than one, so what
 * matters is that exactly the requested one comes out.
 */
class QuickPlayArgumentsTest {

	private static final String MANIFEST = """
			{
			  "arguments": {
			    "game": [
			      "--username", "${auth_player_name}",
			      "--version", "${version_name}",
			      { "rules": [{ "action": "allow", "features": { "is_demo_user": true } }],
			        "value": "--demo" },
			      { "rules": [{ "action": "allow", "features": { "has_quick_plays_support": true } }],
			        "value": ["--quickPlayPath", "${quickPlayPath}"] },
			      { "rules": [{ "action": "allow", "features": { "is_quick_play_singleplayer": true } }],
			        "value": ["--quickPlaySingleplayer", "${quickPlaySingleplayer}"] },
			      { "rules": [{ "action": "allow", "features": { "is_quick_play_multiplayer": true } }],
			        "value": ["--quickPlayMultiplayer", "${quickPlayMultiplayer}"] },
			      { "rules": [{ "action": "allow", "features": { "is_quick_play_realms": true } }],
			        "value": ["--quickPlayRealms", "${quickPlayRealms}"] }
			    ]
			  }
			}
			""";

	private static JsonObject manifest() {
		return JsonParser.parseString(MANIFEST).getAsJsonObject();
	}

	private static Map<String, String> baseValues() {
		return new java.util.LinkedHashMap<>(Map.of(
				"auth_player_name", "somebody",
				"version_name", "26.2"));
	}

	@Test
	@DisplayName("an ordinary launch carries no quick-play argument at all")
	void plainLaunch() {
		List<String> args = LaunchBuilder.gameArguments(manifest(), baseValues(), Set.of());

		assertEquals(List.of("--username", "somebody", "--version", "26.2"), args);
	}

	@Test
	@DisplayName("rejoining a server emits that one argument and only that one")
	void rejoinServer() {
		LaunchBuilder.QuickPlay quickPlay = new LaunchBuilder.QuickPlay("multiplayer", "play.example.net");

		Map<String, String> values = baseValues();
		values.put(quickPlay.placeholder(), quickPlay.target());
		List<String> args = LaunchBuilder.gameArguments(manifest(), values, Set.of(quickPlay.feature()));

		assertTrue(args.contains("--quickPlayMultiplayer"));
		assertEquals("play.example.net", args.get(args.indexOf("--quickPlayMultiplayer") + 1));

		// The failure this is really guarding: any second one of these and the game
		// refuses to start, which shows up long after the mistake was made.
		assertFalse(args.contains("--quickPlaySingleplayer"));
		assertFalse(args.contains("--quickPlayRealms"));
		assertFalse(args.contains("--quickPlayPath"));
		assertFalse(args.contains("--demo"));
	}

	@Test
	@DisplayName("reopening a world uses the save folder's name")
	void reopenWorld() {
		LaunchBuilder.QuickPlay quickPlay = new LaunchBuilder.QuickPlay("singleplayer", "New World (1)");

		Map<String, String> values = baseValues();
		values.put(quickPlay.placeholder(), quickPlay.target());
		List<String> args = LaunchBuilder.gameArguments(manifest(), values, Set.of(quickPlay.feature()));

		assertEquals("New World (1)", args.get(args.indexOf("--quickPlaySingleplayer") + 1));
		assertFalse(args.contains("--quickPlayMultiplayer"));
	}

	@Test
	@DisplayName("the feature name and placeholder match what the manifest actually asks for")
	void namesMatchTheManifest() {
		// Spelled out rather than derived, so a change to the derivation has to be
		// deliberate: these strings are Mojang's, not ours.
		assertEquals("is_quick_play_multiplayer",
				new LaunchBuilder.QuickPlay("multiplayer", "x").feature());
		assertEquals("quickPlayMultiplayer",
				new LaunchBuilder.QuickPlay("multiplayer", "x").placeholder());
		assertEquals("is_quick_play_singleplayer",
				new LaunchBuilder.QuickPlay("singleplayer", "x").feature());
		assertEquals("quickPlaySingleplayer",
				new LaunchBuilder.QuickPlay("singleplayer", "x").placeholder());
	}
}
