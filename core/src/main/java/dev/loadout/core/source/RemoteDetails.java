package dev.loadout.core.source;

import java.util.List;

/**
 * Everything about one mod worth putting on a page.
 *
 * <p>Exists so the description can be read inside the launcher rather than by sending
 * somebody to a browser. That is not only convenience: leaving the application to read
 * what a mod does, then coming back to install it, is the single most interrupted thing
 * in every launcher that does not do this.
 *
 * @param bodyFormat "markdown" or "html", since the two registries disagree and each
 *     needs different handling before it can be rendered safely
 * @param gallery screenshots the author published, which say more than the text
 * @param links external pages -- source, issues, wiki -- kept separate from the body so
 *     they can be presented as links rather than found inside prose
 */
public record RemoteDetails(
		SourceId source,
		String id,
		String slug,
		String title,
		String summary,
		String author,
		long downloads,
		long followers,
		String iconUrl,
		String body,
		String bodyFormat,
		String licence,
		String updatedAt,
		List<String> categories,
		List<GalleryImage> gallery,
		List<Link> links
) {
	/** @param title may be null; plenty of screenshots are unlabelled */
	public record GalleryImage(String url, String title, String description) {
	}

	public record Link(String label, String url) {
	}
}
