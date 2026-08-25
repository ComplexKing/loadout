package dev.loadout.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/** Fetches mod files into the store, verifying them on the way in. */
public final class Downloader {
	private static final String USER_AGENT = "loadout/0.1.0 (Minecraft profile manager)";

	private final HttpClient http;
	private final ModStore store;

	public Downloader(ModStore store) {
		this.store = store;
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(20))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	/** Called as each file starts, so a caller can show progress. */
	public interface Progress {
		void starting(String fileName, long bytes);
	}

	/**
	 * Ensures a version's file is in the store, downloading it only if it isn't already.
	 *
	 * <p>The skip matters more than it sounds: moving between Minecraft versions
	 * repeatedly, or sharing libraries between profiles, means most of what a migration
	 * "downloads" is already on disk from the last time.
	 *
	 * @return the hash it's stored under
	 */
	public String fetch(ModrinthVersion version, Progress progress) throws IOException, InterruptedException {
		if (version.downloadUrl() == null) {
			throw new IOException("No downloadable file for " + version.versionNumber());
		}

		if (version.sha512() != null && this.store.has(version.sha512())) {
			return version.sha512();
		}

		if (progress != null) {
			progress.starting(version.fileName(), version.fileSize());
		}

		Path temp = Files.createTempFile("loadout-", ".jar");
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(version.downloadUrl()))
					.header("User-Agent", USER_AGENT)
					.timeout(Duration.ofMinutes(10))
					.GET()
					.build();

			HttpResponse<InputStream> response =
					this.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() / 100 != 2) {
				throw new IOException("Download failed with " + response.statusCode()
						+ " for " + version.fileName());
			}

			try (InputStream body = response.body()) {
				Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
			}

			// put() re-hashes and rejects a mismatch, so a truncated or tampered download
			// can never reach a profile.
			return this.store.put(temp, version.sha512());
		} finally {
			Files.deleteIfExists(temp);
		}
	}
}
