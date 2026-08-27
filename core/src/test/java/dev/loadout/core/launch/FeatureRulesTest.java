package dev.loadout.core.launch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Feature-guarded launch arguments.
 *
 * <p>This has bitten once already. Minecraft's manifest describes all four quick-play
 * arguments -- singleplayer, multiplayer, realms and a path -- each behind its own feature
 * flag, and the game refuses to start if more than one is given. Emitting every one of
 * them produced a failure that only appeared after fifty mods had finished loading, which
 * is a slow way to learn about a one-line mistake.
 *
 * <p>The fix at the time was to reject every feature-guarded rule, which was correct while
 * nothing used them. Now that rejoining a server does, these check the narrower rule:
 * exactly the flags asked for, and no others.
 */
class FeatureRulesTest {

	/** Shaped exactly like the entries in a real version manifest. */
	private static JsonArray rulesRequiring(String feature) {
		return JsonParser.parseString(
						"[{\"action\":\"allow\",\"features\":{\"" + feature + "\":true}}]")
				.getAsJsonArray();
	}

	@Test
	@DisplayName("a feature-guarded rule is refused when nothing is enabled")
	void refusedByDefault() {
		JsonArray rules = rulesRequiring("is_quick_play_multiplayer");

		assertFalse(LibraryResolver.allowed(rules, Set.of()));
		// The no-argument form is what every ordinary launch uses, and must stay closed.
		assertFalse(LibraryResolver.allowed(rules));
	}

	@Test
	@DisplayName("only the enabled feature is allowed, never its siblings")
	void onlyTheEnabledOne() {
		Set<String> enabled = Set.of("is_quick_play_multiplayer");

		assertTrue(LibraryResolver.allowed(rulesRequiring("is_quick_play_multiplayer"), enabled));

		// The whole point: the game refuses to start when given more than one of these.
		assertFalse(LibraryResolver.allowed(rulesRequiring("is_quick_play_singleplayer"), enabled));
		assertFalse(LibraryResolver.allowed(rulesRequiring("is_quick_play_realms"), enabled));
		assertFalse(LibraryResolver.allowed(rulesRequiring("has_quick_plays_support"), enabled));
	}

	@Test
	@DisplayName("a rule needing several features needs all of them")
	void allOfThem() {
		JsonArray rules = JsonParser.parseString(
						"[{\"action\":\"allow\",\"features\":"
								+ "{\"is_demo_user\":true,\"has_custom_resolution\":true}}]")
				.getAsJsonArray();

		assertFalse(LibraryResolver.allowed(rules, Set.of("is_demo_user")));
		assertTrue(LibraryResolver.allowed(rules, Set.of("is_demo_user", "has_custom_resolution")));
	}

	@Test
	@DisplayName("a rule requiring a feature to be off is allowed only while it is off")
	void negatedFeature() {
		JsonArray rules = JsonParser.parseString(
						"[{\"action\":\"allow\",\"features\":{\"is_demo_user\":false}}]")
				.getAsJsonArray();

		assertTrue(LibraryResolver.allowed(rules, Set.of()));
		assertFalse(LibraryResolver.allowed(rules, Set.of("is_demo_user")));
	}

	@Test
	@DisplayName("rules without features are unaffected")
	void plainRulesStillWork() {
		JsonArray allowAll = JsonParser.parseString("[{\"action\":\"allow\"}]").getAsJsonArray();

		assertTrue(LibraryResolver.allowed(allowAll, Set.of()));
		assertTrue(LibraryResolver.allowed(allowAll, Set.of("is_quick_play_multiplayer")));
	}
}
