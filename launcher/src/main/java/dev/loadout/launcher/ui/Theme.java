package dev.loadout.launcher.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import java.awt.Color;
import java.awt.Font;
import javax.swing.UIManager;

/**
 * Colours, type and the base look.
 *
 * <p>FlatLaf handles the widgets — real rounded controls, a dark theme that actually
 * applies, scrollbars that don't look like 1998. What's left, and what this file is, is
 * the part that makes an interface feel considered rather than merely themed: a palette
 * with intent, one type scale, and consistent spacing.
 *
 * <p>Inter is bundled rather than looked up on the system. Typography carries most of the
 * impression, and falling back to whatever a machine happens to have is how a design ends
 * up looking different for every user.
 */
public final class Theme {
	// A near-black that leans slightly blue. Pure #000 is harsh on a large surface and
	// leaves no room for a card to sit *above* the background.
	public static final Color BG = new Color(0x14161A);
	public static final Color SURFACE = new Color(0x1C1F24);
	public static final Color SURFACE_HOVER = new Color(0x24282F);
	public static final Color SURFACE_HIGH = new Color(0x2A2F37);
	public static final Color LINE = new Color(0x2E333B);

	public static final Color TEXT = new Color(0xEDEFF2);
	public static final Color TEXT_DIM = new Color(0x9AA1AC);
	public static final Color TEXT_FAINT = new Color(0x6B727C);

	/** A green-teal. Distinct from Modrinth's green, warm enough to read as a highlight. */
	public static final Color ACCENT = new Color(0x2FBF87);
	public static final Color ACCENT_HOVER = new Color(0x38D398);
	public static final Color ACCENT_TEXT = new Color(0x0C1A14);

	public static final Color GOOD = new Color(0x4FB477);
	public static final Color WARN = new Color(0xD9A03A);
	public static final Color BAD = new Color(0xE0574A);

	public static final int RADIUS = 10;
	public static final int GAP = 12;

	private static Font base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);

	private Theme() {
	}

	public static void install() {
		FlatInterFont.installLazy();
		FlatDarkLaf.setup();

		UIManager.put("defaultFont", new Font(FlatInterFont.FAMILY, Font.PLAIN, 13));
		base = UIManager.getFont("defaultFont");

		// Rounded everything, matching the card radius so the whole window agrees.
		UIManager.put("Component.arc", RADIUS);
		UIManager.put("Button.arc", RADIUS);
		UIManager.put("TextComponent.arc", RADIUS);
		UIManager.put("ProgressBar.arc", RADIUS);
		UIManager.put("ScrollBar.thumbArc", 999);
		UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
		UIManager.put("ScrollBar.width", 12);
		UIManager.put("ScrollBar.track", BG);
		UIManager.put("ScrollBar.thumb", SURFACE_HIGH);

		UIManager.put("Panel.background", BG);
		UIManager.put("Viewport.background", BG);
		UIManager.put("ScrollPane.background", BG);
		UIManager.put("TabbedPane.background", BG);
		UIManager.put("TabbedPane.contentAreaColor", BG);
		UIManager.put("TabbedPane.selectedBackground", BG);
		UIManager.put("TabbedPane.underlineColor", ACCENT);
		UIManager.put("TabbedPane.tabHeight", 36);

		UIManager.put("Component.focusColor", ACCENT);
		UIManager.put("Component.focusedBorderColor", ACCENT);
		UIManager.put("Component.borderColor", LINE);
		UIManager.put("Separator.foreground", LINE);

		UIManager.put("TextField.background", SURFACE);
		UIManager.put("TextArea.background", SURFACE);
		UIManager.put("List.background", BG);
		UIManager.put("Table.background", BG);

		UIManager.put("Label.foreground", TEXT);
		UIManager.put("TitledBorder.titleColor", TEXT_DIM);
	}

	// One type scale. Every size in the interface comes from here, so nothing drifts.

	public static Font title() {
		return base.deriveFont(Font.BOLD, 20f);
	}

	public static Font heading() {
		return base.deriveFont(Font.BOLD, 15f);
	}

	public static Font strong() {
		return base.deriveFont(Font.BOLD, 13f);
	}

	public static Font body() {
		return base.deriveFont(Font.PLAIN, 13f);
	}

	public static Font small() {
		return base.deriveFont(Font.PLAIN, 12f);
	}

	public static Font tiny() {
		return base.deriveFont(Font.BOLD, 11f);
	}

	public static Font mono() {
		return new Font(Font.MONOSPACED, Font.PLAIN, 12);
	}
}
