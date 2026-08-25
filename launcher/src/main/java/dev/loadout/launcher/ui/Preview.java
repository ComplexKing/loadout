package dev.loadout.launcher.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Renders the interface to an image without putting a window on screen.
 *
 * <p>Design needs looking at, and the alternative is opening a real window on someone's
 * desktop every time a colour changes. A Swing component will paint into any Graphics2D,
 * including one backed by an image, so the whole window can be laid out and captured
 * offscreen — which makes the visual result reviewable the same way a test result is.
 */
public final class Preview {
	private Preview() {
	}

	/**
	 * Writes screenshots of the main window and the browse dialog.
	 *
	 * @param outputDir where the PNGs go
	 */
	public static void render(Path outputDir, dev.loadout.core.LoadoutHome home) throws Exception {
		Theme.install();

		MainWindow[] windowRef = new MainWindow[1];
		SwingUtilities.invokeAndWait(() -> {
			windowRef[0] = new MainWindow(home);
			windowRef[0].setSize(1180, 760);
		});

		// Profiles, mod lists and icons all arrive on background threads. Capturing
		// straight away photographs an empty window, so wait for the work to land before
		// looking -- the same reason a screenshot test needs a settle step.
		settle();
		SwingUtilities.invokeAndWait(() -> {
			try {
				capture(windowRef[0], outputDir.resolve("main.png"));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});

		var names = home.profileNames();
		if (names.isEmpty()) {
			return;
		}

		var profile = home.loadProfile(names.get(0));
		BrowseDialog[] dialogRef = new BrowseDialog[1];
		SwingUtilities.invokeAndWait(() -> {
			dialogRef[0] = new BrowseDialog(windowRef[0],
					new dev.loadout.core.browse.ModInstaller(home),
					home.sources(), new IconCache(home.root()), profile, message -> { });
			dialogRef[0].setSize(980, 720);
		});

		settle();
		SwingUtilities.invokeAndWait(() -> {
			try {
				capture(dialogRef[0], outputDir.resolve("browse.png"));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	/** Waits for background loads and icon downloads, pumping the event queue meanwhile. */
	private static void settle() throws Exception {
		for (int i = 0; i < 12; i++) {
			Thread.sleep(500);
			SwingUtilities.invokeAndWait(() -> { });
		}
	}

	/**
	 * Lays a window out and paints it into a file.
	 *
	 * <p>{@code addNotify} then {@code validate} is what makes this work: layout managers
	 * only assign bounds once a component is realised, and painting an unrealised tree
	 * produces a blank image with everything stacked at 0,0.
	 */
	private static void capture(java.awt.Window window, Path destination) throws Exception {
		window.addNotify();
		window.validate();

		JComponent content = window instanceof JFrame frame
				? (JComponent) frame.getContentPane()
				: (JComponent) ((JDialog) window).getContentPane();
		content.setSize(window.getSize());
		content.doLayout();
		layoutDeep(content);

		BufferedImage image = new BufferedImage(
				window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(Theme.BG);
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		content.paint(g);
		g.dispose();

		File file = destination.toFile();
		file.getParentFile().mkdirs();
		ImageIO.write(image, "png", file);
		System.out.println("wrote " + file);
	}

	/** Forces layout all the way down; doLayout only handles one level. */
	private static void layoutDeep(java.awt.Container container) {
		container.doLayout();
		for (java.awt.Component child : container.getComponents()) {
			if (child instanceof java.awt.Container nested) {
				layoutDeep(nested);
			}
		}
	}
}
