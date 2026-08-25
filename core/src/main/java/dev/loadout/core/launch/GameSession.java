package dev.loadout.core.launch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * A running Minecraft process, with its output captured.
 *
 * <p>Output goes through {@link LogRedactor} before it reaches a screen or a file, so a
 * log that gets pasted somewhere public was never a credential in the first place. It is
 * also captured in full rather than truncated: the useful part of a modded crash is
 * usually the mixin trace hundreds of lines in, and a launcher that trims to the last
 * hundred lines throws away the part worth reading.
 */
public final class GameSession implements AutoCloseable {
	private final Process process;
	private final Path logFile;
	private final Thread pump;

	private GameSession(Process process, Path logFile, Thread pump) {
		this.process = process;
		this.logFile = logFile;
		this.pump = pump;
	}

	/**
	 * Starts the game.
	 *
	 * @param command the full command line, already built
	 * @param workingDir the profile directory, which becomes the game folder
	 * @param logFile where to record output, or null for none
	 * @param onLine called for every line, already redacted
	 */
	public static GameSession start(List<String> command, Path workingDir, Path logFile,
			LogRedactor redactor, Consumer<String> onLine) throws IOException {
		Files.createDirectories(workingDir);

		ProcessBuilder builder = new ProcessBuilder(command)
				.directory(workingDir.toFile())
				// One stream: interleaving stdout and stderr as the process emits them
				// keeps a stack trace next to the log line that caused it.
				.redirectErrorStream(true);

		Process process = builder.start();

		Thread pump = Thread.ofVirtual().name("loadout-log").start(() -> {
			Writer writer = null;
			try {
				if (logFile != null) {
					Files.createDirectories(logFile.getParent());
					writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
							StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
					writer.write("# loadout session " + Instant.now() + System.lineSeparator());
				}

				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						String safe = redactor.redact(line);
						if (onLine != null) {
							onLine.accept(safe);
						}
						if (writer != null) {
							writer.write(safe);
							writer.write(System.lineSeparator());
							// Flushed per line so a crash still leaves a complete log --
							// buffering loses exactly the last lines that explain it.
							writer.flush();
						}
					}
				}
			} catch (IOException e) {
				if (onLine != null) {
					onLine.accept("[loadout] log capture stopped: " + e.getMessage());
				}
			} finally {
				if (writer != null) {
					try {
						writer.close();
					} catch (IOException ignored) {
						// Nothing useful to do; the process outcome is what matters.
					}
				}
			}
		});

		return new GameSession(process, logFile, pump);
	}

	public boolean isRunning() {
		return this.process.isAlive();
	}

	public Path logFile() {
		return this.logFile;
	}

	/** Waits for the game to exit and returns its exit code. */
	public int awaitExit() throws InterruptedException {
		int code = this.process.waitFor();
		this.pump.join(java.time.Duration.ofSeconds(5));
		return code;
	}

	public void stop() {
		this.process.destroy();
	}

	@Override
	public void close() {
		if (this.process.isAlive()) {
			this.process.destroy();
		}
	}
}
