package dev.loadout.launcher.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

/**
 * Loads and caches mod icons.
 *
 * <p>Icons are most of what makes a browser feel like a storefront rather than a
 * spreadsheet, but a list of forty of them is forty HTTP requests — so they load off the
 * event thread, arrive one at a time, and are cached twice: in memory for the session and
 * on disk so a second run is instant and works offline.
 */
public final class IconCache {
	/** Small pool: enough that a visible page fills quickly, not so many that we hammer a CDN. */
	private static final ExecutorService POOL = Executors.newFixedThreadPool(6, runnable -> {
		Thread thread = new Thread(runnable, "loadout-icons");
		thread.setDaemon(true);  // never hold the app open for a decorative image
		return thread;
	});

	private static final Map<String, ImageIcon> MEMORY = new ConcurrentHashMap<>();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private final Path diskCache;

	public IconCache(Path loadoutRoot) {
		this.diskCache = loadoutRoot.resolve("cache").resolve("icons");
	}

	/**
	 * Fetches an icon, calling back on the event thread when it's ready.
	 *
	 * <p>Returns immediately. A card asks for its icon and keeps its placeholder until one
	 * arrives, so a slow or missing image never blocks the list from being usable.
	 *
	 * @param url may be null; plenty of projects have no icon
	 */
	public void load(String url, int size, Consumer<ImageIcon> onReady) {
		if (url == null || url.isBlank()) {
			return;
		}

		String key = url + "@" + size;
		ImageIcon cached = MEMORY.get(key);
		if (cached != null) {
			Ui.onUi(() -> onReady.accept(cached));
			return;
		}

		POOL.submit(() -> {
			try {
				BufferedImage image = fetch(url);
				if (image == null) {
					return;
				}

				ImageIcon icon = new ImageIcon(round(scale(image, size), size, Theme.RADIUS));
				MEMORY.put(key, icon);
				Ui.onUi(() -> onReady.accept(icon));
			} catch (Exception e) {
				// A missing icon is cosmetic. Cards fall back to their initial.
			}
		});
	}

	private BufferedImage fetch(String url) throws Exception {
		Path onDisk = this.diskCache.resolve(hash(url) + ".png");
		if (Files.isRegularFile(onDisk)) {
			return ImageIO.read(onDisk.toFile());
		}

		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", "loadout/0.1.0")
				.timeout(Duration.ofSeconds(20))
				.GET()
				.build();

		HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() / 100 != 2) {
			return null;
		}

		BufferedImage image;
		try (InputStream body = response.body()) {
			image = ImageIO.read(body);
		}
		if (image == null) {
			return null;  // Modrinth serves some SVGs, which ImageIO can't read
		}

		try {
			Files.createDirectories(onDisk.getParent());
			ImageIO.write(image, "png", onDisk.toFile());
		} catch (Exception e) {
			// An uncacheable icon still displays this session.
		}
		return image;
	}

	/** Scales with bilinear filtering; nearest-neighbour on a 256px icon looks terrible. */
	private static BufferedImage scale(BufferedImage source, int size) {
		BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = scaled.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(source, 0, 0, size, size, null);
		g.dispose();
		return scaled;
	}

	/** Clips to a rounded square, so icons match the cards they sit in. */
	private static BufferedImage round(BufferedImage source, int size, int radius) {
		BufferedImage rounded = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = rounded.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(Color.WHITE);
		g.fill(new RoundRectangle2D.Float(0, 0, size, size, radius, radius));
		g.setComposite(java.awt.AlphaComposite.SrcIn);
		g.drawImage(source, 0, 0, null);
		g.dispose();
		return rounded;
	}

	private static String hash(String value) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		StringBuilder hex = new StringBuilder(32);
		for (int i = 0; i < 16; i++) {
			hex.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
			hex.append(Character.forDigit(bytes[i] & 0xF, 16));
		}
		return hex.toString();
	}
}
