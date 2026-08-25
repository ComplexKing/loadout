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
	 * Ensures a source's file is in the store, downloading it only if it isn't already.
	 *
	 * <p>Sources differ in what they publish about a file. Modrinth gives a SHA-512, so a
	 * download can be verified against what was advertised; CurseForge publishes a
	 * different fingerprint scheme, so there is nothing to check against and the hash is
	 * whatever the bytes turn out to be. Both end up addressed by their real hash either
	 * way -- verification is a bonus where it exists, not a precondition.
	 */
	public String fetch(dev.loadout.core.source.RemoteFile file, Progress progress)
			throws IOException, InterruptedException {
		if (!file.isDownloadable()) {
			throw new IOException(file.fileName() + " cannot be downloaded from "
					+ file.source().displayName() + " - the author has opted out of third-party downloads");
		}
		return fetch(file.downloadUrl(), file.fileName(), file.sha512(), file.fileSize(), progress);
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
		return fetch(version.downloadUrl(), version.fileName(), version.sha512(),
				version.fileSize(), progress);
	}

	private String fetch(String url, String fileName, String expectedSha512, long size, Progress progress)
			throws IOException, InterruptedException {
		if (expectedSha512 != null && this.store.has(expectedSha512)) {
			return expectedSha512;
		}

		if (progress != null) {
			progress.starting(fileName, size);
		}

		Path temp = Files.createTempFile("loadout-", ".jar");
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.header("User-Agent", USER_AGENT)
					.timeout(Duration.ofMinutes(10))
					.GET()
					.build();

			HttpResponse<InputStream> response =
					this.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() / 100 != 2) {
				throw new IOException("Download failed with " + response.statusCode()
						+ " for " + fileName);
			}

			try (InputStream body = response.body()) {
				Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
			}

			// put() re-hashes and rejects a mismatch, so a truncated or tampered download
			// can never reach a profile.
			return this.store.put(temp, expectedSha512);
		} finally {
			Files.deleteIfExists(temp);
		}
	}
}
