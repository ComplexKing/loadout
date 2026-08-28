'use strict';

const { spawn } = require('node:child_process');
const path = require('node:path');
const fs = require('node:fs');
const readline = require('node:readline');

/**
 * Owns the loadout.jar child process.
 *
 * The jar is the whole application; this window is a view onto it. That split is what
 * keeps a CLI-only install a first-class thing rather than a stripped-down mode, and it
 * means the logic has one implementation instead of one per front end.
 *
 * Startup is a handshake rather than a fixed port: the jar binds port 0, so the OS picks
 * something free, and prints its port and access token as one JSON line on stdout. A
 * fixed port would collide with whatever else the user runs, and a token file would have
 * to live somewhere predictable, be readable by every process the user owns, and be
 * cleaned up after a crash. A pipe has none of those problems and closes itself.
 */
class Backend {
	constructor() {
		this.process = null;
		this.port = null;
		this.token = null;
	}

	/** Where the jar lives, packaged or in a source checkout. */
	static jarPath() {
		// process.resourcesPath only exists in a packaged app, where electron-builder has
		// copied the jar in beside the app bundle.
		if (process.resourcesPath && !process.defaultApp) {
			const packaged = path.join(process.resourcesPath, 'loadout.jar');
			if (fs.existsSync(packaged)) {
				return packaged;
			}
		}

		const checkout = path.resolve(__dirname, '..', '..', '..', 'build', 'dist', 'loadout.jar');
		if (fs.existsSync(checkout)) {
			return checkout;
		}

		throw new Error(
			'loadout.jar not found. Build it from the project root with: gradlew dist');
	}

	/**
	 * Starts the jar and waits for it to announce itself.
	 *
	 * @returns {Promise<{port: number, token: string}>}
	 */
	/**
	 * The Java to run the jar with.
	 *
	 * <p>Prefers the runtime shipped beside the app. Loadout needs Java 21 and a machine
	 * that has never run a Java program has none -- asking somebody to go and install a JDK
	 * before an installer will work is most of the reason people give up on a launcher.
	 * The bundled one is minimal: the eight modules the jar actually uses, about 50 MB.
	 *
	 * <p>Falls back to JAVA_HOME and then the PATH, which is what a checkout uses.
	 */
	static javaPath() {
		const exe = process.platform === 'win32' ? 'java.exe' : 'java';

		const candidates = [];
		if (process.resourcesPath && !process.defaultApp) {
			candidates.push(path.join(process.resourcesPath, 'runtime', 'bin', exe));
		}
		candidates.push(path.resolve(__dirname, '..', '..', 'runtime', 'bin', exe));
		if (process.env.JAVA_HOME) {
			candidates.push(path.join(process.env.JAVA_HOME, 'bin', exe));
		}

		for (const candidate of candidates) {
			if (fs.existsSync(candidate)) {
				return candidate;
			}
		}
		return 'java';
	}

	start() {
		return new Promise((resolve, reject) => {
			const jar = Backend.jarPath();
			const java = Backend.javaPath();

			this.process = spawn(java, ['-jar', jar, 'serve'], {
				// Inherit nothing: stdout carries the handshake and stderr carries faults,
				// and both need reading rather than forwarding to a console nobody sees.
				stdio: ['ignore', 'pipe', 'pipe'],
				windowsHide: true,
			});

			// Collected so a failure to start can be reported with the actual reason. A
			// bare "exited with code 1" sends people looking in the wrong place.
			let stderr = '';
			this.process.stderr.on('data', (chunk) => {
				stderr += chunk.toString();
				if (stderr.length > 8192) {
					stderr = stderr.slice(-8192);
				}
			});

			this.process.on('error', (error) => {
				reject(error.code === 'ENOENT'
					? new Error('Java was not found. Loadout needs Java 21 or newer: https://adoptium.net')
					: error);
			});

			this.process.on('exit', (code) => {
				// Only meaningful before the handshake; afterwards the promise has settled
				// and this is a crash for the caller's exit handler to deal with.
				reject(new Error(`Loadout exited with code ${code}. ${stderr.trim()}`.trim()));
			});

			const lines = readline.createInterface({ input: this.process.stdout });
			lines.on('line', (line) => {
				let hello;
				try {
					hello = JSON.parse(line);
				} catch {
					// Not the handshake. A JVM can print warnings before anything else runs,
					// so skipping unparseable lines is normal rather than an error.
					return;
				}

				if (!hello.ready) {
					return;
				}

				lines.close();
				this.port = hello.port;
				this.token = hello.token;
				resolve({ port: hello.port, token: hello.token });
			});
		});
	}

	stop() {
		if (this.process && this.process.exitCode === null) {
			// The jar installs a shutdown hook, so a signal lets it close its own sockets
			// and finish writing rather than leaving a half-written profile.
			this.process.kill();
		}
	}
}

module.exports = { Backend };
