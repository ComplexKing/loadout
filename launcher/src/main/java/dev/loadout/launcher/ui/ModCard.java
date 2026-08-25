package dev.loadout.launcher.ui;

import dev.loadout.core.Profile;
import dev.loadout.core.browse.SearchResult;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * One mod, as a card.
 *
 * <p>Laid out with GridBagLayout rather than nested BoxLayouts. BoxLayout distributes
 * space by preferred size and will happily squeeze a label below the width its text
 * needs — which silently clips names to "Fabric AP" and leaves descriptions floating in
 * the middle of a row. GridBag lets exactly one column absorb the slack, so the text
 * block gets all remaining width and everything else keeps its natural size.
 */
public final class ModCard {
	private static final int ICON = 56;

	private ModCard() {
	}

	/** A search result, with an install action. */
	public static Card forSearch(SearchResult result, IconCache icons, Runnable onInstall) {
		Card card = new Card();
		card.setLayout(new GridBagLayout());
		card.setBorder(Ui.pad(16, 18, 16, 18));
		Ui.capHeight(card, 108);

		GridBagConstraints c = new GridBagConstraints();

		// Icon: fixed size, spanning the card's full height.
		c.gridx = 0;
		c.gridy = 0;
		c.gridheight = 3;
		c.anchor = GridBagConstraints.NORTHWEST;
		c.insets = new Insets(0, 0, 0, 16);
		card.add(iconFor(result.title(), result.iconUrl(), icons), c);

		// Title and author share a line, the author trailing at a lower weight.
		// BorderLayout, not FlowLayout: Flow clips whatever doesn't fit, which is why the
		// author was appearing as "by modmuss5". West keeps its size, centre takes the rest.
		JPanel titleRow = new JPanel(new BorderLayout(8, 0));
		titleRow.setOpaque(false);
		titleRow.add(Ui.elide(result.title(), Theme.heading(), Theme.TEXT), BorderLayout.WEST);
		if (result.author() != null) {
			titleRow.add(Ui.elide("by " + result.author(), Theme.small(), Theme.TEXT_FAINT),
					BorderLayout.CENTER);
		}

		c.gridx = 1;
		c.gridy = 0;
		c.gridheight = 1;
		c.weightx = 1;                                  // this column absorbs the slack
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(0, 0, 4, 12);
		card.add(titleRow, c);

		JLabel description = Ui.elide(result.description(), Theme.body(), Theme.TEXT_DIM);
		// A preferred width of zero lets the column decide; without it the label demands
		// room for the whole string and pushes the install button off the card.
		description.setPreferredSize(new Dimension(0, 18));
		c.gridy = 1;
		c.insets = new Insets(0, 0, 8, 12);
		card.add(description, c);

		JPanel tags = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		tags.setOpaque(false);
		tags.add(Ui.pill(result.downloadsShort() + " downloads", Theme.TEXT_DIM, Theme.SURFACE_HIGH));
		int shown = 0;
		for (String category : result.categories()) {
			if (shown++ >= 3) {
				break;
			}
			tags.add(Ui.pill(category, Theme.TEXT_FAINT, Theme.SURFACE_HIGH));
		}

		c.gridy = 2;
		c.insets = new Insets(0, 0, 0, 12);
		card.add(tags, c);

		JButton install = Ui.primary("Install");
		install.setPreferredSize(new Dimension(96, 34));
		install.addActionListener(event -> onInstall.run());

		c.gridx = 2;
		c.gridy = 0;
		c.gridheight = 3;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		c.anchor = GridBagConstraints.CENTER;
		c.insets = new Insets(0, 0, 0, 0);
		card.add(install, c);

		return card;
	}

	/** An installed mod, with an on/off switch and a remove action. */
	public static Card forInstalled(Profile.Entry entry, IconCache icons,
			java.util.function.Consumer<Boolean> onToggle, Runnable onRemove) {
		Card card = new Card();
		card.setLayout(new GridBagLayout());
		card.setBorder(Ui.pad(14, 18, 14, 18));
		Ui.capHeight(card, 78);

		String name = entry.modId() == null ? entry.fileName() : entry.modId();
		GridBagConstraints c = new GridBagConstraints();

		c.gridx = 0;
		c.gridy = 0;
		c.gridheight = 2;
		c.anchor = GridBagConstraints.WEST;
		c.insets = new Insets(0, 0, 0, 14);
		// Installed jars are on disk, not on Modrinth, so there is no icon url to fetch --
		// the derived avatar is the image, and it stays the same colour per mod.
		card.add(Ui.avatar(name, 42), c);

		JPanel titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		titleRow.setOpaque(false);
		titleRow.add(Ui.elide(name, Theme.strong(), Theme.TEXT));
		if (entry.versionNumber() != null) {
			titleRow.add(Ui.pill(entry.versionNumber(), Theme.TEXT_DIM, Theme.SURFACE_HIGH));
		}
		if (!entry.enabled()) {
			titleRow.add(Ui.pill("disabled", Theme.WARN, Theme.SURFACE_HIGH));
		}

		c.gridx = 1;
		c.gridy = 0;
		c.gridheight = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(0, 0, 3, 12);
		card.add(titleRow, c);

		JLabel file = Ui.elide(entry.fileName(), Theme.small(), Theme.TEXT_FAINT);
		file.setPreferredSize(new Dimension(0, 16));
		c.gridy = 1;
		c.insets = new Insets(0, 0, 0, 12);
		card.add(file, c);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
		actions.setOpaque(false);

		JCheckBox enabled = new JCheckBox();
		enabled.setSelected(entry.enabled());
		enabled.setOpaque(false);
		enabled.setToolTipText("Enable or disable without removing the file");
		enabled.addActionListener(event -> onToggle.accept(enabled.isSelected()));

		JButton remove = Ui.ghost("Remove");
		remove.setPreferredSize(new Dimension(88, 32));
		remove.addActionListener(event -> onRemove.run());

		actions.add(enabled);
		actions.add(remove);

		c.gridx = 2;
		c.gridy = 0;
		c.gridheight = 2;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		c.anchor = GridBagConstraints.CENTER;
		c.insets = new Insets(0, 0, 0, 0);
		card.add(actions, c);

		return card;
	}

	/**
	 * The icon, starting as a derived avatar and swapping in the real image when it lands.
	 *
	 * <p>Showing a placeholder immediately keeps the list at a stable size while images
	 * arrive; reserving blank space instead makes the page jump as each one appears.
	 */
	private static JComponent iconFor(String name, String url, IconCache icons) {
		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.setPreferredSize(new Dimension(ICON, ICON));
		holder.setMinimumSize(new Dimension(ICON, ICON));
		holder.add(Ui.avatar(name, ICON), BorderLayout.CENTER);

		icons.load(url, ICON, image -> {
			holder.removeAll();
			holder.add(new JLabel(image), BorderLayout.CENTER);
			holder.revalidate();
			holder.repaint();
		});

		return holder;
	}
}
