package dev.loadout.launcher.ui;

import dev.loadout.core.Profile;
import dev.loadout.core.browse.ModBrowser;
import dev.loadout.core.browse.ModInstaller;
import dev.loadout.core.browse.SearchResult;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

/**
 * Browse Modrinth and install into a profile.
 *
 * <p>Results are filtered to the profile's Minecraft version and loader before the
 * request goes out, so everything on screen is genuinely installable. Showing everything
 * and letting people discover incompatibility at install time is how a browser ends up
 * mostly full of things that won't work.
 */
public final class BrowseDialog extends JDialog {
	private final ModInstaller installer;
	private final IconCache icons;
	private final Profile profile;
	private final Consumer<String> onInstalled;

	private final JTextField query = new JTextField();
	private final JComboBox<String> sort = new JComboBox<>(
			new String[] { "Most downloaded", "Most relevant", "Recently updated", "Newest" });
	private final JPanel results = Ui.column();
	private final JLabel status = Ui.dim("");
	// Reserved width so a growing count doesn't get clipped by BorderLayout.WEST.
	private static final Dimension STATUS_SIZE = new Dimension(420, 20);

	public BrowseDialog(JFrame parent, ModInstaller installer, IconCache icons,
			Profile profile, Consumer<String> onInstalled) {
		super(parent, "Browse mods", true);
		this.installer = installer;
		this.icons = icons;
		this.profile = profile;
		this.onInstalled = onInstalled;

		setSize(960, 680);
		setLocationRelativeTo(parent);
		getContentPane().setBackground(Theme.BG);

		add(buildHeader(), BorderLayout.NORTH);
		add(Ui.scroll(wrapTop(this.results)), BorderLayout.CENTER);
		add(buildFooter(), BorderLayout.SOUTH);

		// Open on the most popular compatible mods rather than an empty box, so the
		// dialog is useful before anyone types.
		search("");
	}

	private Component buildHeader() {
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(Theme.SURFACE);
		panel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.LINE),
				Ui.pad(16, 20, 16, 20)));

		JPanel titles = new JPanel(new java.awt.GridLayout(2, 1, 0, 3));
		titles.setOpaque(false);
		titles.add(Ui.heading("Add to " + this.profile.name()));
		titles.add(Ui.dim("Showing only mods that work on Minecraft "
				+ this.profile.minecraftVersion() + " with " + this.profile.loader()));

		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(titles, BorderLayout.NORTH);

		JPanel controls = Ui.row();
		controls.setBorder(Ui.pad(14, 0, 0, 0));
		this.query.putClientProperty("JTextField.placeholderText", "Search mods...");
		this.query.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
		this.query.addActionListener(event -> search(this.query.getText()));

		JButton go = Ui.primary("Search");
		go.addActionListener(event -> search(this.query.getText()));
		this.sort.setMaximumSize(new Dimension(180, 36));
		this.sort.addActionListener(event -> search(this.query.getText()));

		controls.add(this.query);
		controls.add(Ui.gap(10));
		controls.add(this.sort);
		controls.add(Ui.gap(10));
		controls.add(go);
		top.add(controls, BorderLayout.CENTER);

		panel.add(top, BorderLayout.CENTER);
		return panel;
	}

	private Component buildFooter() {
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(Theme.SURFACE);
		panel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE),
				Ui.pad(10, 20, 10, 20)));

		JButton close = Ui.ghost("Done");
		close.addActionListener(event -> dispose());

		this.status.setPreferredSize(STATUS_SIZE);
		panel.add(this.status, BorderLayout.WEST);
		panel.add(close, BorderLayout.EAST);
		return panel;
	}

	private static Component wrapTop(JPanel content) {
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setOpaque(false);
		content.setBorder(Ui.pad(16, 20, 16, 20));
		wrapper.add(content, BorderLayout.NORTH);
		return wrapper;
	}

	/** Maps the friendly sort names onto Modrinth's index names. */
	private String sortIndex() {
		return switch (this.sort.getSelectedIndex()) {
			case 1 -> "relevance";
			case 2 -> "updated";
			case 3 -> "newest";
			default -> "downloads";
		};
	}

	private void search(String text) {
		this.status.setText("Searching...");

		new SwingWorker<List<SearchResult>, Void>() {
			@Override
			protected List<SearchResult> doInBackground() throws Exception {
				return new ModBrowser().search(text, profile.minecraftVersion(),
						profile.loader(), sortIndex(), 40, 0);
			}

			@Override
			protected void done() {
				try {
					List<SearchResult> found = get();
					results.removeAll();

					for (SearchResult result : found) {
						results.add(ModCard.forSearch(result, icons, () -> install(result)));
						results.add(Ui.gap(10));
					}
					if (found.isEmpty()) {
						results.add(Ui.body("Nothing matched. Try a different search."));
					}

					results.revalidate();
					results.repaint();
					status.setText(found.size() + " result" + (found.size() == 1 ? "" : "s"));
				} catch (Exception e) {
					Throwable cause = e.getCause() == null ? e : e.getCause();
					status.setText("Search failed: " + cause.getMessage());
				}
			}
		}.execute();
	}

	private void install(SearchResult chosen) {
		this.status.setText("Installing " + chosen.title() + "...");

		new SwingWorker<ModInstaller.Result, String>() {
			@Override
			protected ModInstaller.Result doInBackground() throws Exception {
				return installer.install(profile.name(), chosen.projectId(),
						(file, bytes) -> publish(file));
			}

			@Override
			protected void process(List<String> files) {
				status.setText("Downloading " + files.get(files.size() - 1));
			}

			@Override
			protected void done() {
				try {
					ModInstaller.Result result = get();
					onInstalled.accept(summarise(chosen, result));
					status.setText(summarise(chosen, result));

					if (!result.unavailable().isEmpty()) {
						JOptionPane.showMessageDialog(BrowseDialog.this,
								chosen.title() + " needs these, and none have a build for "
								+ profile.minecraftVersion() + ":\n\n  "
								+ String.join("\n  ", result.unavailable()),
								"Missing dependencies", JOptionPane.WARNING_MESSAGE);
					}
				} catch (Exception e) {
					Throwable cause = e.getCause() == null ? e : e.getCause();
					status.setText("Install failed: " + cause.getMessage());
				}
			}
		}.execute();
	}

	private static String summarise(SearchResult chosen, ModInstaller.Result result) {
		int added = result.installed().size();
		int upgraded = result.upgraded().size();

		if (added == 0 && upgraded == 0) {
			return chosen.title() + " was already installed";
		}

		StringBuilder out = new StringBuilder("Installed " + chosen.title());
		// People expect one file and get four. Naming the dependencies makes the extra
		// jars look deliberate rather than alarming.
		if (added > 1) {
			out.append(" and ").append(added - 1)
					.append(added - 1 == 1 ? " dependency" : " dependencies");
		}
		if (upgraded > 0) {
			out.append(", upgraded ").append(upgraded).append(" existing");
		}
		return out.toString();
	}
}
