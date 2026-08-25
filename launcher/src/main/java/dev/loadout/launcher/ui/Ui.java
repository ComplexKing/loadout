package dev.loadout.launcher.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;

/** Small building blocks, so spacing and type stay consistent everywhere. */
public final class Ui {
	private Ui() {
	}

	public static void onUi(Runnable action) {
		if (SwingUtilities.isEventDispatchThread()) {
			action.run();
		} else {
			SwingUtilities.invokeLater(action);
		}
	}

	// --- text ---

	public static JLabel label(String text, java.awt.Font font, Color color) {
		JLabel label = new JLabel(text);
		label.setFont(font);
		label.setForeground(color);
		return label;
	}

	public static JLabel title(String text) {
		return label(text, Theme.title(), Theme.TEXT);
	}

	public static JLabel heading(String text) {
		return label(text, Theme.heading(), Theme.TEXT);
	}

	public static JLabel strong(String text) {
		return label(text, Theme.strong(), Theme.TEXT);
	}

	public static JLabel body(String text) {
		return label(text, Theme.body(), Theme.TEXT_DIM);
	}

	public static JLabel dim(String text) {
		return label(text, Theme.small(), Theme.TEXT_FAINT);
	}

	/**
	 * A small rounded tag, for a version or a count.
	 *
	 * <p>Pills carry the metadata that would otherwise need its own column. Putting the
	 * download count in a chip rather than a table cell is why a card can say as much as a
	 * row while staying scannable.
	 */
	public static JComponent pill(String text, Color foreground, Color background) {
		JLabel label = new JLabel(text) {
			@Override
			protected void paintComponent(Graphics graphics) {
				Graphics2D g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(background);
				g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 999, 999));
				g.dispose();
				super.paintComponent(graphics);
			}
		};
		label.setFont(Theme.tiny());
		label.setForeground(foreground);
		label.setOpaque(false);
		label.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
		return label;
	}

	// --- controls ---

	public static JButton button(String text) {
		JButton button = new JButton(text);
		button.setFont(Theme.strong());
		button.setFocusPainted(false);
		button.putClientProperty("JButton.buttonType", "roundRect");
		return button;
	}

	public static JButton primary(String text) {
		JButton button = button(text);
		button.setBackground(Theme.ACCENT);
		button.setForeground(Theme.ACCENT_TEXT);
		button.putClientProperty("JButton.focusedBackground", Theme.ACCENT_HOVER);
		return button;
	}

	public static JButton ghost(String text) {
		JButton button = button(text);
		button.setBackground(Theme.SURFACE_HIGH);
		button.setForeground(Theme.TEXT);
		return button;
	}

	// --- layout ---

	public static JPanel column() {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		return panel;
	}

	public static JPanel row() {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
		panel.setOpaque(false);
		return panel;
	}

	public static Component gap(int size) {
		return Box.createRigidArea(new Dimension(size, size));
	}

	public static Component glue() {
		return Box.createGlue();
	}

	public static Border pad(int top, int left, int bottom, int right) {
		return BorderFactory.createEmptyBorder(top, left, bottom, right);
	}

	public static <T extends JComponent> T padded(T component, int amount) {
		component.setBorder(pad(amount, amount, amount, amount));
		return component;
	}

	/**
	 * A scroll pane with no border and a faster wheel.
	 *
	 * <p>Swing's default of three lines per notch feels sluggish against a list of cards,
	 * where one notch should move roughly one item.
	 */
	public static JScrollPane scroll(Component content) {
		JScrollPane pane = new JScrollPane(content);
		pane.setBorder(null);
		pane.setViewportBorder(null);
		pane.getViewport().setBackground(Theme.BG);
		pane.setBackground(Theme.BG);
		pane.getVerticalScrollBar().setUnitIncrement(24);
		pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		return pane;
	}

	/** Stops a BoxLayout stretching a card to fill all remaining height. */
	public static void capHeight(JComponent component, int height) {
		component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		component.setPreferredSize(new Dimension(component.getPreferredSize().width, height));
	}

	/**
	 * A coloured square with an initial, for when a mod has no icon.
	 *
	 * <p>Deriving the colour from the name means the same mod is always the same colour,
	 * so a list stays recognisable at a glance even where images are missing.
	 */
	public static JComponent avatar(String name, int size) {
		String initial = name == null || name.isBlank()
				? "?" : name.substring(0, 1).toUpperCase();
		Color background = colorFor(name == null ? "" : name);

		JLabel label = new JLabel(initial, JLabel.CENTER) {
			@Override
			protected void paintComponent(Graphics graphics) {
				Graphics2D g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(background);
				g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), Theme.RADIUS, Theme.RADIUS));
				g.dispose();
				super.paintComponent(graphics);
			}
		};
		label.setFont(Theme.body().deriveFont(java.awt.Font.BOLD, size * 0.42f));
		label.setForeground(Color.WHITE);
		label.setPreferredSize(new Dimension(size, size));
		label.setMinimumSize(new Dimension(size, size));
		label.setMaximumSize(new Dimension(size, size));
		return label;
	}

	/**
	 * A label that shortens its own text to fit, ending in an ellipsis.
	 *
	 * <p>Swing's layout managers clip a label that doesn't fit rather than shortening it,
	 * so a name becomes "Fabric AP" and reads as a bug. Measuring the string and trimming
	 * it means overflow always looks deliberate.
	 */
	public static JLabel elide(String text, java.awt.Font font, Color color) {
		return new JLabel(text) {
			{
				setFont(font);
				setForeground(color);
			}

			@Override
			protected void paintComponent(Graphics graphics) {
				// The string is drawn directly rather than pushed back through setText.
				// Calling setText during a paint doesn't affect the paint already in
				// progress -- which is why the first attempt still clipped hard instead
				// of showing an ellipsis -- and it schedules a revalidate on every frame.
				Graphics2D g = (Graphics2D) graphics.create();
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
						RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				g.setFont(getFont());
				g.setColor(getForeground());

				java.awt.FontMetrics metrics = g.getFontMetrics();
				java.awt.Insets insets = getInsets();
				int available = getWidth() - insets.left - insets.right;
				String shown = fit(text == null ? "" : text, metrics, available);

				int baseline = insets.top + (getHeight() - insets.top - insets.bottom
						- metrics.getHeight()) / 2 + metrics.getAscent();
				g.drawString(shown, insets.left, baseline);
				g.dispose();
			}

			@Override
			public Dimension getMinimumSize() {
				// Zero minimum, so a layout gives this whatever is left rather than
				// reserving room for the untruncated string.
				return new Dimension(0, super.getMinimumSize().height);
			}
		};
	}

	/** Trims a string to fit a width, ending in an ellipsis when it has to. */
	static String fit(String text, java.awt.FontMetrics metrics, int available) {
		if (available <= 0 || metrics.stringWidth(text) <= available) {
			return text;
		}

		int ellipsis = metrics.stringWidth("...");
		int end = text.length();
		while (end > 0 && metrics.stringWidth(text.substring(0, end)) + ellipsis > available) {
			end--;
		}
		return text.substring(0, Math.max(0, end)).stripTrailing() + "...";
	}

	/** Muted, evenly spread hues so no two neighbours collide and none of them shout. */
	static Color colorFor(String name) {
		int hash = Math.abs(name.hashCode());
		float hue = (hash % 360) / 360f;
		return Color.getHSBColor(hue, 0.34f, 0.52f);
	}
}
