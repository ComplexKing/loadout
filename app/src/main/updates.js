'use strict';

const { autoUpdater } = require('electron-updater');

/**
 * Keeping an installed Loadout current.
 *
 * <p>Checks GitHub Releases, downloads a newer build in the background, and then stops and
 * waits. Installing means restarting the app, and restarting the app underneath somebody
 * who is halfway through installing a mod -- or who has a game running that this process
 * is watching -- is not an improvement over being one version behind. So the download is
 * automatic and the restart never is.
 *
 * <p>Silent about nearly everything. A launcher that reports "you are up to date" every
 * time it opens has turned a non-event into a notification; only an update that is ready
 * to apply is worth the user's attention.
 */
class Updates {
	/**
	 * @param {() => void} onAvailable told when a version is downloaded and ready
	 * @param {(message: string) => void} log
	 */
	constructor(onAvailable, log) {
		this.ready = null;
		this.onAvailable = onAvailable;
		this.log = log;

		// Downloads happen on our schedule, and installing happens on the user's.
		autoUpdater.autoDownload = true;
		autoUpdater.autoInstallOnAppQuit = false;
		autoUpdater.logger = null;

		autoUpdater.on('update-available', (info) => {
			this.log(`update ${info.version} available, downloading`);
		});

		autoUpdater.on('update-downloaded', (info) => {
			this.ready = info.version;
			this.log(`update ${info.version} ready`);
			this.onAvailable(info.version);
		});

		autoUpdater.on('error', (error) => {
			// Being offline, rate-limited, or behind a proxy that blocks GitHub are all
			// ordinary. None of them is a reason to interrupt somebody.
			this.log(`update check failed: ${error && error.message}`);
		});
	}

	/**
	 * Looks for a newer release.
	 *
	 * <p>Does nothing in a checkout: electron-updater needs the metadata electron-builder
	 * writes beside a packaged app, and calling it from a dev run only produces an error
	 * about a missing app-update.yml.
	 */
	check(isPackaged) {
		if (!isPackaged) {
			this.log('update check skipped: not a packaged build');
			return;
		}
		autoUpdater.checkForUpdates().catch(() => {
			// Already reported through the error handler above.
		});
	}

	/** @returns {string|null} the version waiting, if one is */
	pending() {
		return this.ready;
	}

	/**
	 * Restarts into the downloaded version.
	 *
	 * <p>Only ever called because somebody pressed the button that says so.
	 */
	install() {
		if (!this.ready) {
			return false;
		}
		// isSilent false so the installer's own progress is visible; isForceRunAfter so
		// the app comes back, which is the entire expectation when you press Restart.
		autoUpdater.quitAndInstall(false, true);
		return true;
	}
}

module.exports = { Updates };
