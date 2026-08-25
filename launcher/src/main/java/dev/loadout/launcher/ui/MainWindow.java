package dev.loadout.launcher.ui;

import dev.loadout.core.LoadoutHome;
import dev.loadout.core.MigrationPlan;
import dev.loadout.core.MigrationPlanner;
import dev.loadout.core.ModJar;
import dev.loadout.core.ModScanner;
import dev.loadout.core.ModrinthClient;
import dev.loadout.core.Profile;
import dev.loadout.core.ProfileManager;
import dev.loadout.core.browse.ModInstaller;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.io.File;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

/**
 * The main window.
 *
 * <p>Everything touching the network or disk runs on a {@link SwingWorker}. Swing paints
 * on one thread, and planning a migration is several seconds of HTTP — inline it would
 * freeze the window mid-render.
 */
public final class MainWindow extends JFrame {
	private final LoadoutHome home;
	private final ProfileManager profiles;
	private final ModInstaller installer;
	private final IconCache icons;

	private final JPanel profileList = Ui.column();
	private final JPanel modList = Ui.column();
	private final JTextArea planArea = new JTextArea();
	private final JTextArea logArea = new JTextArea();
	private final JTextField targetVersion = new JTextField(8);

	private final JLabel profileTitle = Ui.title("No profile");
	private final JPanel profileMeta = Ui.row();
	private final JLabel status = Ui.dim("Ready");

	private String selected;

	public MainWindow(LoadoutHome home) {
		super("Loadout");
		this.home = home;
		this.profiles = new ProfileManager(home);
		this.installer = new ModInstaller(home);
		this.icons = new IconCache(home.root());

		setDefaultCloseOperation(EXIT_ON_CLOSE);
		setSize(1180, 740);
		setMinimumSize(new Dimension(940, 600));
		setLocationRelativeTo(null);
		getContentPane().setBackground(Theme.BG);

		add(buildSidebar(), BorderLayout.WEST);
		add(buildMain(), BorderLayout.CENTER);
		add(buildStatusBar(), BorderLayout.SOUTH);

		refreshProfiles();
	}

	// --- structure ---

	private JComponent buildSidebar() {
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(Theme.SURFACE);
		panel.setPreferredSize(new Dimension(268, 0));
		panel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.LINE));

		// BorderLayout rather than a BoxLayout column: BoxLayout sizes children to their
		// preferred width and was clipping the heading to "Profile".
		JPanel header = new JPanel(new BorderLayout());
		header.setOpaque(false);
		header.setBorder(Ui.pad(22, 20, 16, 20));
		JPanel brandRows = new JPanel(new java.awt.GridLayout(2, 1, 0, 2));
		brandRows.setOpaque(false);
		brandRows.add(Ui.title("Loadout"));
		brandRows.add(Ui.dim("Profiles"));
		header.add(brandRows, BorderLayout.WEST);

		this.profileList.setBorder(Ui.pad(0, 12, 12, 12));

		JButton importButton = Ui.ghost("Import a mods folder");
		importButton.addActionListener(event -> importFolder());
		JPanel footer = Ui.column();
		footer.setBorder(Ui.pad(8, 16, 16, 16));
		importButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		importButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		footer.add(importButton);

		panel.add(header, BorderLayout.NORTH);
		panel.add(Ui.scroll(wrapTop(this.profileList)), BorderLayout.CENTER);
		panel.add(footer, BorderLayout.SOUTH);
		return panel;
	}

	private JComponent buildMain() {
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(Theme.BG);

		JPanel header = new JPanel(new BorderLayout());
		header.setOpaque(false);
		header.setBorder(Ui.pad(20, 24, 12, 24));

		JPanel left = Ui.column();
		this.profileTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		this.profileMeta.setAlignmentX(Component.LEFT_ALIGNMENT);
		left.add(this.profileTitle);
		left.add(Ui.gap(6));
		left.add(this.profileMeta);

		JButton launch = Ui.primary("Play");
		launch.setPreferredSize(new Dimension(120, 38));
		launch.addActionListener(event -> launchSelected());

		header.add(left, BorderLayout.WEST);
		header.add(launch, BorderLayout.EAST);

		JTabbedPane tabs = new JTabbedPane();
		tabs.setBorder(Ui.pad(0, 16, 16, 16));
		tabs.addTab("Mods", buildModsTab());
		tabs.addTab("Migrate", buildMigrateTab());
		tabs.addTab("Log", buildLogTab());

		panel.add(header, BorderLayout.NORTH);
		panel.add(tabs, BorderLayout.CENTER);
		return panel;
	}

	private JComponent buildModsTab() {
		JPanel panel = new JPanel(new BorderLayout());
		panel.setOpaque(false);

		JPanel bar = new JPanel(new BorderLayout());
		bar.setOpaque(false);
		bar.setBorder(Ui.pad(10, 8, 10, 8));
		JButton add = Ui.primary("Browse mods");
		add.addActionListener(event -> openBrowser());
		bar.add(add, BorderLayout.WEST);

		this.modList.setBorder(Ui.pad(0, 8, 8, 8));

		panel.add(bar, BorderLayout.NORTH);
		panel.add(Ui.scroll(wrapTop(this.modList)), BorderLayout.CENTER);
		return panel;
	}

	private JComponent buildMigrateTab() {
		JPanel panel = new JPanel(new BorderLayout());
		panel.setOpaque(false);

		JPanel controls = Ui.row();
		controls.setBorder(Ui.pad(12, 8, 12, 8));
		controls.add(Ui.body("Move to Minecraft"));
		controls.add(Ui.gap(10));
		this.targetVersion.setMaximumSize(new Dimension(120, 34));
		controls.add(this.targetVersion);
		controls.add(Ui.gap(10));

		JButton check = Ui.ghost("Check");
		check.addActionListener(event -> planMigration(false));
		JButton migrate = Ui.primary("Migrate");
		migrate.addActionListener(event -> planMigration(true));
		controls.add(check);
		controls.add(Ui.gap(8));
		controls.add(migrate);
		controls.add(Ui.glue());

		this.planArea.setEditable(false);
		this.planArea.setFont(Theme.mono());
		this.planArea.setBackground(Theme.SURFACE);
		this.planArea.setForeground(Theme.TEXT_DIM);
		this.planArea.setBorder(Ui.pad(14, 16, 14, 16));
		this.planArea.setText("""
				Pick a profile, choose a Minecraft version, and press Check.

				Nothing changes until you press Migrate, and a snapshot is taken first,
				so it can always be undone.""");

		panel.add(controls, BorderLayout.NORTH);
		panel.add(Ui.scroll(this.planArea), BorderLayout.CENTER);
		return panel;
	}

	private JComponent buildLogTab() {
		this.logArea.setEditable(false);
		this.logArea.setFont(Theme.mono());
		this.logArea.setBackground(Theme.SURFACE);
		this.logArea.setForeground(Theme.TEXT_DIM);
		this.logArea.setBorder(Ui.pad(14, 16, 14, 16));
		this.logArea.setText("""
				Game output appears here.

				Access tokens are stripped before anything is shown or written to disk,
				so a log you paste somewhere for help was never a credential.""");
		return Ui.scroll(this.logArea);
	}

	private JComponent buildStatusBar() {
		JPanel bar = new JPanel(new BorderLayout());
		bar.setBackground(Theme.SURFACE);
		bar.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE),
				Ui.pad(8, 16, 8, 16)));
		bar.add(this.status, BorderLayout.WEST);

		// The home path is long enough to run off the window. Show the tail, which is the
		// part that identifies it, and put the whole thing in a tooltip.
		String path = this.home.root().toString();
		String shown = path.length() > 52 ? "..." + path.substring(path.length() - 49) : path;
		JLabel location = Ui.dim(shown);
		location.setToolTipText(path);
		bar.add(location, BorderLayout.EAST);
		return bar;
	}

	/** Keeps a BoxLayout column packed to the top instead of spread over the viewport. */
	private static JComponent wrapTop(JComponent content) {
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setOpaque(false);
		wrapper.add(content, BorderLayout.NORTH);
		return wrapper;
	}

	// --- data ---

	private void refreshProfiles() {
		background("Loading profiles", this.home::profileNames, names -> {
			this.profileList.removeAll();

			for (String name : names) {
				Card card = new Card();
				card.setLayout(new BorderLayout(10, 0));
				card.setBorder(Ui.pad(10, 12, 10, 12));
				card.colors(Theme.SURFACE, Theme.SURFACE_HOVER).shadow(false);
				Ui.capHeight(card, 56);
				card.add(Ui.avatar(name, 34), BorderLayout.WEST);

				JPanel text = Ui.column();
				text.add(Ui.strong(name));
				card.add(text, BorderLayout.CENTER);

				card.setSelected(name.equals(this.selected));
				card.onClick(() -> select(name));

				this.profileList.add(card);
				this.profileList.add(Ui.gap(6));
			}

			this.profileList.revalidate();
			this.profileList.repaint();

			if (this.selected == null && !names.isEmpty()) {
				select(names.get(0));
			}
			setStatus(names.isEmpty() ? "No profiles yet - import a mods folder to start" : "Ready");
		});
	}

	private void select(String name) {
		this.selected = name;
		for (Component component : this.profileList.getComponents()) {
			if (component instanceof Card card) {
				card.setSelected(false);
			}
		}
		refreshProfiles();
		loadProfile(name);
	}

	private void loadProfile(String name) {
		background("Reading " + name, () -> this.home.loadProfile(name), profile -> {
			this.profileTitle.setText(profile.name());

			this.profileMeta.removeAll();
			this.profileMeta.add(Ui.pill("Minecraft " + profile.minecraftVersion(),
					Theme.ACCENT, Theme.SURFACE_HIGH));
			this.profileMeta.add(Ui.gap(6));
			this.profileMeta.add(Ui.pill(profile.loader(), Theme.TEXT_DIM, Theme.SURFACE_HIGH));
			this.profileMeta.add(Ui.gap(6));
			this.profileMeta.add(Ui.pill(profile.enabledMods().size() + " of "
					+ profile.mods().size() + " enabled", Theme.TEXT_DIM, Theme.SURFACE_HIGH));
			this.profileMeta.add(Ui.glue());

			this.modList.removeAll();
			for (Profile.Entry entry : profile.mods()) {
				this.modList.add(ModCard.forInstalled(entry, this.icons,
						enabled -> toggle(name, entry.fileName(), enabled),
						() -> remove(name, entry.fileName())));
				this.modList.add(Ui.gap(8));
			}

			if (profile.mods().isEmpty()) {
				this.modList.add(Ui.body("No mods yet. Press Browse mods to add some."));
			}

			this.targetVersion.setText(profile.minecraftVersion());
			revalidate();
			repaint();
			setStatus(profile.mods().size() + " mods in " + name);
		});
	}

	// --- actions ---

	private void toggle(String profile, String file, boolean enabled) {
		background("Updating " + file, () -> this.installer.setEnabled(profile, file, enabled), ok -> {
			loadProfile(profile);
			setStatus(file + (enabled ? " enabled" : " disabled"));
		});
	}

	private void remove(String profile, String file) {
		int confirm = JOptionPane.showConfirmDialog(this,
				"Remove " + file + "?\n\nA snapshot is taken first, so this can be undone.",
				"Remove mod", JOptionPane.OK_CANCEL_OPTION);
		if (confirm != JOptionPane.OK_OPTION) {
			return;
		}

		background("Removing " + file, () -> this.installer.remove(profile, file), removed -> {
			loadProfile(profile);
			setStatus(removed ? "Removed " + file : "Not found");
		});
	}

	private void importFolder() {
		JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		chooser.setDialogTitle("Choose a mods folder");
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}

		File folder = chooser.getSelectedFile();
		String name = JOptionPane.showInputDialog(this, "Name for this profile:", folder.getName());
		if (name == null || name.isBlank()) {
			return;
		}
		String version = JOptionPane.showInputDialog(this, "Which Minecraft version?", "26.2");
		if (version == null || version.isBlank()) {
			return;
		}

		background("Importing " + name,
				() -> this.profiles.importFrom(folder.toPath(), name.trim(), version.trim(), "fabric"),
				profile -> {
					this.selected = profile.name();
					refreshProfiles();
					setStatus("Imported " + profile.mods().size() + " mods");
				});
	}

	private void openBrowser() {
		if (this.selected == null) {
			setStatus("Pick a profile first");
			return;
		}

		background("Reading " + this.selected, () -> this.home.loadProfile(this.selected), profile ->
				new BrowseDialog(this, this.installer, this.home.sources(), this.icons, profile, message -> {
					loadProfile(profile.name());
					setStatus(message);
				}).setVisible(true));
	}

	private void planMigration(boolean apply) {
		String name = this.selected;
		String target = this.targetVersion.getText().trim();
		if (name == null || target.isEmpty()) {
			setStatus("Pick a profile and a target version");
			return;
		}

		this.planArea.setText("Checking " + name + " against Minecraft " + target + "...");

		background("Checking " + name, () -> {
			Profile profile = this.home.loadProfile(name);
			List<ModJar> mods = ModScanner.scan(this.home.modsDir(name));
			MigrationPlan plan = new MigrationPlanner(new ModrinthClient())
					.plan(mods, target, profile.loader());

			if (!apply) {
				return describe(plan, target);
			}
			if (!plan.isClean()) {
				return describe(plan, target) + "\n\nNot migrating: " + plan.blockers().size()
						+ " mod(s) have no build for " + target + ".\n"
						+ "Leaving them behind would almost certainly stop the game starting.";
			}

			ProfileManager.MigrationResult result = this.profiles.apply(profile, plan, true, null);
			return describe(plan, target) + "\n\nMigrated. " + result.modsChanged()
					+ " mods changed.\nSnapshot " + result.snapshotId() + " was taken first.";
		}, text -> {
			this.planArea.setText(text);
			this.planArea.setCaretPosition(0);
			loadProfile(name);
		});
	}

	private static String describe(MigrationPlan plan, String target) {
		StringBuilder out = new StringBuilder();
		section(out, "Would update", plan.changes(), true);
		section(out, "Already fine", plan.withStatus(MigrationPlan.Status.ALREADY_SUPPORTED), false);
		section(out, "Likely matches - confirm these", plan.needsConfirming(), true);
		section(out, "No build for " + target, plan.withStatus(MigrationPlan.Status.NO_BUILD), false);
		section(out, "Not on Modrinth", plan.withStatus(MigrationPlan.Status.UNKNOWN), false);
		section(out, "Disabled, ignored", plan.withStatus(MigrationPlan.Status.DISABLED), false);

		out.append('\n');
		out.append(plan.isClean()
				? "This profile can move to " + target + "."
				: plan.blockers().size() + " mod(s) would block the move.");
		return out.toString();
	}

	private static void section(StringBuilder out, String title,
			List<MigrationPlan.Entry> entries, boolean showTarget) {
		if (entries.isEmpty()) {
			return;
		}
		out.append(title).append("  (").append(entries.size()).append(")\n");
		for (MigrationPlan.Entry entry : entries) {
			out.append("   ").append(entry.jar().displayName());
			if (showTarget && entry.target() != null) {
				out.append("   ->   ").append(entry.target().versionNumber());
			}
			out.append('\n');
		}
		out.append('\n');
	}

	private void launchSelected() {
		if (this.selected == null) {
			setStatus("Pick a profile first");
			return;
		}

		// Launching needs a Microsoft account that has signed in before. Saying so plainly
		// beats a stack trace from deep inside the launcher.
		JOptionPane.showMessageDialog(this,
				"Loadout needs a Microsoft account before it can launch, including offline.\n\n"
				+ "Sign-in is waiting on an approved Azure application. Everything else\n"
				+ "- profiles, mods, browsing, migration - works without it.",
				"No account yet", JOptionPane.INFORMATION_MESSAGE);
	}

	// --- plumbing ---

	private interface Work<T> {
		T run() throws Exception;
	}

	private <T> void background(String what, Work<T> work, java.util.function.Consumer<T> done) {
		setStatus(what + "...");
		new SwingWorker<T, Void>() {
			@Override
			protected T doInBackground() throws Exception {
				return work.run();
			}

			@Override
			protected void done() {
				try {
					done.accept(get());
				} catch (Exception e) {
					Throwable cause = e.getCause() == null ? e : e.getCause();
					setStatus("Failed: " + cause.getMessage());
					JOptionPane.showMessageDialog(MainWindow.this,
							cause.getMessage(), what + " failed", JOptionPane.ERROR_MESSAGE);
				}
			}
		}.execute();
	}

	private void setStatus(String text) {
		Ui.onUi(() -> this.status.setText(text));
	}
}
