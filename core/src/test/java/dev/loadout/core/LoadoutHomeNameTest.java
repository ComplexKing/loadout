package dev.loadout.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Profile names decide directory paths, so this is a security boundary rather than a
 * tidiness check.
 *
 * <p>It mattered less when names could only come from someone typing a CLI argument. Now
 * that they arrive over HTTP from a UI, a name that escapes the profiles folder turns
 * "delete this profile" into "delete that directory" -- so the interesting cases here are
 * the hostile ones.
 */
class LoadoutHomeNameTest {

	@ParameterizedTest
	@ValueSource(strings = {"main", "1.21.1-fabric", "My Profile", "a", "with.dots.inside"})
	@DisplayName("ordinary names are accepted")
	void acceptsOrdinaryNames(String name) {
		assertDoesNotThrow(() -> LoadoutHome.requireValidName(name));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"../evil",
			"../../etc/passwd",
			"..",
			".",
			"foo/bar",
			"foo\\bar",
			"/absolute",
			"C:\\Windows\\System32"
	})
	@DisplayName("anything that could escape the profiles folder is rejected")
	void rejectsTraversal(String name) {
		assertThrows(IllegalArgumentException.class, () -> LoadoutHome.requireValidName(name));
	}

	@ParameterizedTest
	@ValueSource(strings = {"CON", "con", "PRN", "AUX", "NUL", "COM1", "LPT1", "CON.txt"})
	@DisplayName("Windows device names are rejected, extension or not")
	void rejectsReservedNames(String name) {
		// Windows resolves these to devices wherever they appear as a filename, so a
		// profile called CON would open a console handle instead of a directory.
		assertThrows(IllegalArgumentException.class, () -> LoadoutHome.requireValidName(name));
	}

	@ParameterizedTest
	@ValueSource(strings = {" leading", "trailing ", "trailing."})
	@DisplayName("names Windows would silently rewrite are rejected")
	void rejectsAmbiguousWhitespace(String name) {
		// Windows strips trailing dots and spaces, so "foo " and "foo" would be the same
		// directory while appearing in the UI as two different profiles.
		assertThrows(IllegalArgumentException.class, () -> LoadoutHome.requireValidName(name));
	}

	@Test
	@DisplayName("empty, null and oversized names are rejected")
	void rejectsEmptyAndOversized() {
		assertThrows(IllegalArgumentException.class, () -> LoadoutHome.requireValidName(null));
		assertThrows(IllegalArgumentException.class, () -> LoadoutHome.requireValidName(""));
		assertThrows(IllegalArgumentException.class, () -> LoadoutHome.requireValidName("   "));
		assertThrows(IllegalArgumentException.class,
				() -> LoadoutHome.requireValidName("x".repeat(65)));
	}

	@Test
	@DisplayName("control characters are rejected")
	void rejectsControlCharacters() {
		// A NUL is the classic way to truncate a path in a native call underneath.
		assertThrows(IllegalArgumentException.class,
				() -> LoadoutHome.requireValidName("evil" + (char) 0 + ".jar"));
		assertThrows(IllegalArgumentException.class,
				() -> LoadoutHome.requireValidName("line\nbreak"));
	}

	@Test
	@DisplayName("a rejected name never produces a path outside the profiles folder")
	void traversalNeverEscapes() {
		// The property that actually matters, stated directly: whatever profileDir returns
		// stays under the profiles directory. Validation is the mechanism; this is the goal.
		LoadoutHome home = new LoadoutHome(Path.of("test-root"));
		Path profiles = Path.of("test-root", "profiles").toAbsolutePath().normalize();

		for (String hostile : new String[] {"../evil", "..", "foo/bar", "foo\\bar"}) {
			assertThrows(IllegalArgumentException.class, () -> home.profileDir(hostile),
					hostile + " should have been rejected");
		}

		Path safe = home.profileDir("main").toAbsolutePath().normalize();
		assertTrue(safe.startsWith(profiles), "a valid name must resolve inside " + profiles);
	}

	@Test
	@DisplayName("a valid name is returned unchanged")
	void returnsInput() {
		assertEquals("main", LoadoutHome.requireValidName("main"));
	}
}
