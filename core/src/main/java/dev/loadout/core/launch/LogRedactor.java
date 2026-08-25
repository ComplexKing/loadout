package dev.loadout.core.launch;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes secrets from anything that gets shown or written.
 *
 * <p>This exists because of what people actually do when a game won't start: they paste
 * the whole log into a Discord channel. Minecraft is launched with the access token on
 * its command line, and that token is enough to use the account. Crash reports include
 * the command line. So a log is a credential unless something makes it not one.
 *
 * <p>Redaction happens on the way out of the process, before a line reaches a file or a
 * screen, so there is no window where an unredacted copy exists on disk.
 */
public final class LogRedactor {
	private static final String MASK = "[redacted]";

	private final List<Pattern> patterns = new ArrayList<>();
	private final List<String> literals = new ArrayList<>();

	public LogRedactor() {
		// The access token as Minecraft is invoked with it.
		this.patterns.add(Pattern.compile("(--accessToken\\s+)\\S+"));
		this.patterns.add(Pattern.compile("(--clientId\\s+)\\S+"));
		this.patterns.add(Pattern.compile("(--xuid\\s+)\\S+"));
		// Bearer tokens and JWTs, which show up in networking errors.
		this.patterns.add(Pattern.compile("(?i)(authorization:\\s*bearer\\s+)\\S+"));
		this.patterns.add(Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"));
	}

	/**
	 * Registers a value to strike out wherever it appears.
	 *
	 * <p>Patterns only catch secrets in the shape they were expected in. Registering the
	 * literal token as well catches it when a mod logs it somewhere nobody anticipated,
	 * which is exactly the case a pattern list will always miss.
	 */
	public void addSecret(String secret) {
		if (secret != null && secret.length() >= 8) {
			this.literals.add(secret);
		}
	}

	public String redact(String line) {
		if (line == null || line.isEmpty()) {
			return line;
		}

		String result = line;
		for (String secret : this.literals) {
			if (result.contains(secret)) {
				result = result.replace(secret, MASK);
			}
		}
		for (Pattern pattern : this.patterns) {
			result = pattern.matcher(result).replaceAll("$1" + MASK);
		}
		return result;
	}

	/** Redacts a whole command line, for logging what was about to be run. */
	public List<String> redact(List<String> command) {
		List<String> out = new ArrayList<>(command.size());
		boolean maskNext = false;

		for (String argument : command) {
			if (maskNext) {
				out.add(MASK);
				maskNext = false;
				continue;
			}

			// Values arrive as their own argument, so the flag is the signal that the
			// next one is a secret rather than something matchable in isolation.
			if (argument.equals("--accessToken") || argument.equals("--clientId") || argument.equals("--xuid")) {
				maskNext = true;
			}

			out.add(redact(argument));
		}

		return out;
	}
}
