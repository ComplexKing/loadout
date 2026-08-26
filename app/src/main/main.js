'use strict';

const { app, BrowserWindow, ipcMain, shell, dialog } = require('electron');
const path = require('node:path');
const { Backend } = require('./backend');
const { Api } = require('./api');

const backend = new Backend();
let api = null;
let window = null;
let stopEvents = null;
let ready = false;

function createWindow() {
	window = new BrowserWindow({
		width: 1180,
		height: 760,
		minWidth: 900,
		minHeight: 600,
		show: false,
		backgroundColor: '#14161a',
		// The frame is drawn by the page so the sidebar can run the full height, which is
		// most of why Prism and the Modrinth app look like applications rather than forms.
		//
		// No titleBarOverlay: that draws the platform's own buttons on top of the page, in
		// a strip whose width the page has to guess at and stay clear of -- and anything
		// placed near the corner ends up underneath them. Drawing the three buttons here
		// means the whole bar is ours, so nothing can be overlapped and they match.
		titleBarStyle: 'hidden',
		webPreferences: {
			preload: path.join(__dirname, '..', 'preload', 'preload.js'),
			// The renderer displays text written by strangers -- mod titles, descriptions,
			// author names pulled from two public registries. It gets no Node, no direct
			// access to this process's objects, and its own OS sandbox.
			contextIsolation: true,
			nodeIntegration: false,
			sandbox: true,
			webviewTag: false,
			// Chromium stops compositing a window it considers background, which for a
			// launcher is precisely when it has work to show: the game has focus, and the
			// download progress behind it would sit frozen until someone clicked back.
			backgroundThrottling: false,
		},
	});

	// A screenshot run must never put a window on screen. It is used while the machine is
	// being used for something else -- often the game this launcher exists to start -- and
	// a window appearing and stealing focus mid-session is unacceptable. The page still
	// renders and composites offscreen, which is what capturePage needs; background
	// throttling is already disabled above, which is what makes that reliable.
	const offscreen = process.argv.some((arg) => arg.startsWith('--screenshot='));
	if (!offscreen) {
		window.once('ready-to-show', () => window.show());
	}

	window.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));

	// The jar and the page start at the same time and either can win. Announcing on both
	// edges -- when the backend comes up, and when a page finishes loading if it is
	// already up -- is what makes a reload behave the same as a cold start.
	for (const event of ['maximize', 'unmaximize', 'enter-full-screen', 'leave-full-screen']) {
		window.on(event, () => {
			if (window && !window.isDestroyed()) {
				window.webContents.send('window:state', window.isMaximized());
			}
		});
	}

	window.webContents.on('did-finish-load', () => {
		if (ready && window && !window.isDestroyed()) {
			window.webContents.send('backend:ready');
		}
	});

	// With --dev, the page's own console comes through to the terminal. A renderer error
	// is otherwise invisible unless someone has devtools open, which is exactly when a
	// startup failure is hardest to catch.
	if (process.argv.includes('--dev')) {
		const levels = ['debug', 'info', 'warning', 'error'];
		window.webContents.on('console-message', (event) => {
			const level = levels[event.level] || event.level;
			console.log(`[renderer:${level}] ${event.message}`);
		});
		window.webContents.on('render-process-gone', (_event, details) =>
			console.error('[renderer gone]', details.reason));
	}

	// Anything that would navigate the window elsewhere opens in the real browser instead.
	// A mod page is a link, not a place for this window to end up -- and a window that can
	// be navigated to an arbitrary site is a window with a preload script attached to it.
	window.webContents.setWindowOpenHandler(({ url }) => {
		if (url.startsWith('https://')) {
			shell.openExternal(url);
		}
		return { action: 'deny' };
	});

	window.webContents.on('will-navigate', (event, url) => {
		if (!url.startsWith('file://')) {
			event.preventDefault();
			if (url.startsWith('https://')) {
				shell.openExternal(url);
			}
		}
	});
}

/**
 * Wires each API operation to its own channel.
 *
 * Named channels rather than one "call this path" channel. The renderer should be able to
 * ask for the things this application does and nothing else, so the surface is written
 * out rather than forwarded -- and it doubles as the list of what the UI actually needs.
 */
function registerHandlers() {
	const handle = (channel, fn) => ipcMain.handle(channel, async (_event, ...args) => {
		try {
			return { ok: true, data: await fn(...args) };
		} catch (error) {
			// Rejections cross the IPC boundary as opaque strings, so failures are returned
			// as values and the renderer decides how to show them.
			return { ok: false, error: error.message };
		}
	});

	handle('health', () => api.get('/health'));

	handle('sources:list', (verify) => api.get(`/sources?verify=${verify ? 'true' : 'false'}`));
	handle('settings:curseforgeKey', (key) =>
		api.request('PUT', '/settings/curseforge-key', { key }));

	handle('profiles:list', () => api.get('/profiles'));
	handle('profiles:get', (name) => api.get(`/profiles/${encodeURIComponent(name)}`));
	handle('profiles:create', (profile) => api.request('POST', '/profiles', profile));
	handle('profiles:delete', (name) =>
		api.request('DELETE', `/profiles/${encodeURIComponent(name)}`));

	handle('mods:install', (name, source, id, type) =>
		api.request('POST', `/profiles/${encodeURIComponent(name)}/mods`, { source, id, type }));
	handle('mods:remove', (name, fileName) =>
		api.request('DELETE',
			`/profiles/${encodeURIComponent(name)}/mods/${encodeURIComponent(fileName)}`));
	handle('mods:toggle', (name, fileName, enabled) =>
		api.request('PUT',
			`/profiles/${encodeURIComponent(name)}/mods/${encodeURIComponent(fileName)}`,
			{ enabled }));

	handle('mods:icons', (name) => api.get(`/profiles/${encodeURIComponent(name)}/icons`));

	handle('mods:installVersion', (name, source, id, versionId, type) =>
		api.request('POST', `/profiles/${encodeURIComponent(name)}/mods`,
			{ source, id, versionId, type }));

	handle('versions', (source, id, profile) => api.get('/versions?'
		+ new URLSearchParams({ source, id, profile }).toString()));

	handle('snapshots:list', (name) =>
		api.get(`/profiles/${encodeURIComponent(name)}/snapshots`));
	handle('snapshots:rollback', (name, snapshotId) =>
		api.request('POST', `/profiles/${encodeURIComponent(name)}/rollback`, { snapshotId }));

	handle('migrate', (name, target, apply, includeLikely) =>
		api.request('POST', `/profiles/${encodeURIComponent(name)}/migrate`,
			{ target, apply, includeLikely }));

	handle('launch', (name, username) =>
		api.request('POST', `/profiles/${encodeURIComponent(name)}/launch`, { username }));

	handle('search', (query) => {
		const params = new URLSearchParams();
		for (const [key, value] of Object.entries(query || {})) {
			if (value !== undefined && value !== null && value !== '') {
				params.set(key, String(value));
			}
		}
		return api.get(`/search?${params.toString()}`);
	});

	handle('instance:worlds', (name) => api.get(`/profiles/${encodeURIComponent(name)}/worlds`));
	handle('instance:servers', (name) => api.get(`/profiles/${encodeURIComponent(name)}/servers`));
	handle('instance:screenshots', (name) =>
		api.get(`/profiles/${encodeURIComponent(name)}/screenshots`));
	handle('instance:logs', (name) => api.get(`/profiles/${encodeURIComponent(name)}/logs`));
	handle('instance:logTail', (name, log) => api.get(`/profiles/${encodeURIComponent(name)}/log?`
		+ new URLSearchParams({ name: log }).toString()));
	handle('instance:packs', (name, type) => api.get(`/profiles/${encodeURIComponent(name)}/packs?`
		+ new URLSearchParams({ type }).toString()));

	handle('instance:duplicate', (name, target) =>
		api.request('POST', `/profiles/${encodeURIComponent(name)}/duplicate`, { name: target }));
	handle('instance:export', (name, path, options) =>
		api.request('POST', `/profiles/${encodeURIComponent(name)}/export`, { path, ...options }));

	handle('options:get', (name) => api.get(`/profiles/${encodeURIComponent(name)}/options`));
	handle('options:set', (name, options) =>
		api.request('PUT', `/profiles/${encodeURIComponent(name)}/options`, options));
	handle('options:defaults', () => api.get('/settings/game'));
	handle('options:setDefaults', (options) => api.request('PUT', '/settings/game', options));

	handle('accounts:list', () => api.get('/accounts'));
	handle('accounts:signin', () => api.request('POST', '/accounts/signin', {}));
	handle('accounts:complete', (deviceCode, intervalSeconds) =>
		api.request('POST', '/accounts/complete', { deviceCode, intervalSeconds }));
	handle('accounts:primary', (uuid) =>
		api.request('POST', `/accounts/${encodeURIComponent(uuid)}/primary`, {}));
	handle('accounts:skin', (uuid) => api.get(`/accounts/${encodeURIComponent(uuid)}/skin`));
	handle('accounts:setSkin', (uuid, path, variant) =>
		api.request('PUT', `/accounts/${encodeURIComponent(uuid)}/skin`, { path, variant }));

	handle('accounts:remove', (uuid) =>
		api.request('DELETE', `/accounts/${encodeURIComponent(uuid)}`));

	handle('java:list', () => api.get('/java'));
	handle('minecraft:versions', () => api.get('/minecraft/versions'));
	handle('jobs:list', () => api.get('/jobs'));
	handle('jobs:cancel', (id) => api.request('POST', `/jobs/${encodeURIComponent(id)}/cancel`));

	// Links are opened here rather than by the page, so the renderer can never hand an
	// arbitrary scheme to the OS. Only https survives the check.
	/**
	 * Reveals a folder in the file manager.
	 *
	 * <p>Restricted to paths inside Loadout's own data directory. The renderer supplies the
	 * path, and openPath on an arbitrary string would hand the page a way to launch
	 * whatever it liked through the shell.
	 */
	/**
	 * Asks for a skin file.
	 *
	 * <p>A sandboxed page can read a file the user picks but cannot learn its path, and the
	 * jar needs a path to upload from. The native dialog is the only thing that can bridge
	 * that, and it also means the page never chooses what gets read.
	 */
	ipcMain.handle('accounts:chooseSkin', async () => {
		const chosen = await dialog.showOpenDialog(window, {
			title: 'Choose a skin',
			filters: [{ name: 'Skin', extensions: ['png'] }],
			properties: ['openFile'],
		});
		return chosen.canceled || chosen.filePaths.length === 0
			? { ok: false, error: 'Cancelled' }
			: { ok: true, data: chosen.filePaths[0] };
	});

	/**
	 * The window buttons.
	 *
	 * <p>Separate channels rather than one taking a name, so the page cannot ask for an
	 * action that was never meant to be offered.
	 */
	ipcMain.handle('window:minimize', () => {
		if (window && !window.isDestroyed()) window.minimize();
		return { ok: true };
	});

	ipcMain.handle('window:toggleMaximize', () => {
		if (!window || window.isDestroyed()) return { ok: true, data: false };
		if (window.isMaximized()) {
			window.unmaximize();
		} else {
			window.maximize();
		}
		return { ok: true, data: window.isMaximized() };
	});

	ipcMain.handle('window:close', () => {
		if (window && !window.isDestroyed()) window.close();
		return { ok: true };
	});

	ipcMain.handle('window:isMaximized', () =>
		({ ok: true, data: Boolean(window && !window.isDestroyed() && window.isMaximized()) }));

	ipcMain.handle('open:path', async (_event, target) => {
		if (typeof target !== 'string' || !target) {
			return { ok: false, error: 'No path given' };
		}
		try {
			const home = (await api.get('/health')).home;
			const resolved = path.resolve(target);
			if (!resolved.startsWith(path.resolve(home))) {
				return { ok: false, error: 'That path is outside the Loadout folder' };
			}
			shell.openPath(resolved);
			return { ok: true };
		} catch (error) {
			return { ok: false, error: error.message };
		}
	});

	ipcMain.handle('open:external', (_event, url) => {
		if (typeof url === 'string' && url.startsWith('https://')) {
			shell.openExternal(url);
			return { ok: true };
		}
		return { ok: false, error: 'Only https links can be opened' };
	});
}

/**
 * Renders the window to a PNG and exits.
 *
 * The same reason the Swing build grew a preview command: iterating on a layout should
 * not mean a window repeatedly seizing focus on someone's desktop, and a screenshot is
 * reviewable without being present when it was taken.
 *
 * @param target path to write
 */
async function screenshotAndQuit(target) {
	// Data has to have arrived, not just the document. A capture on did-finish-load shows
	// an empty list every time, because the profiles are still a request away.
	await new Promise((resolve) => setTimeout(resolve, 2500));

	// These drive the page to whichever state is worth looking at, so a screenshot can
	// show search results or an instance rather than only the first screen.
	const argOf = (name) => {
		const found = process.argv.find((arg) => arg.startsWith(`--${name}=`));
		return found ? found.slice(name.length + 3) : null;
	};

	const run = (js) => window.webContents.executeJavaScript(js);
	const settle = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

	// Matched in JavaScript rather than built into a selector string: an instance name can
	// contain spaces and quotes, which makes an attribute selector a parse error.
	const pick = (selector, value) => run(`(() => {
		const found = [...document.querySelectorAll(${JSON.stringify(selector)})]
			.find((node) => node.dataset.name === ${JSON.stringify(value)}
				|| node.dataset.view === ${JSON.stringify(value)}
				|| node.dataset.tab === ${JSON.stringify(value)});
		if (found) { found.click(); }
	})()`);

	const open = argOf('open');
	if (open) {
		await pick('.rail-tile', open);
		await settle(900);
	}

	const view = argOf('view');
	if (view) {
		await pick('.rail-btn[data-view]', view);
		await settle(600);
	}

	const tab = argOf('tab');
	if (tab) {
		// The instance sub-nav; .seg is the Installed/Add switch, which is a different thing.
		await pick('#subnav .subnav-item', tab);
		await settle(900);
	}

	const query = argOf('query');
	if (query) {
		// Whichever search box is on screen. Dispatching input rather than setting value
		// alone is what makes the page's own debounce and request actually run.
		await run(`(() => {
			const box = [...document.querySelectorAll('input[type=search]')]
				.find((i) => i.offsetParent !== null && i.id !== 'mod-filter');
			if (box) { box.value = ${JSON.stringify(query)}; box.dispatchEvent(new Event('input')); }
		})()`);
	}

	// Long enough for two registries to answer and their artwork to arrive.
	if (query || tab || view || open) {
		await settle(4200);
	}

	// A last click once everything has settled, for states that only exist after the page
	// has data -- a dialog opened from a search result, say.
	// Several selectors separated by | so a state two clicks deep can be reached, such as
	// a dropdown inside a dialog.
	const click = argOf('click');
	if (click) {
		for (const selector of click.split('|')) {
			await run(`(() => {
				const node = document.querySelector(${JSON.stringify(selector.trim())});
				if (!node) { return; }
				node.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
				node.click();
			})()`);
			await settle(1500);
		}
		await settle(1200);
	}

	// Arbitrary page script, for driving states no sequence of clicks reaches -- filling a
	// form, say. --eval-file avoids passing a whole script through argv, where shell
	// quoting mangles anything with newlines or nested quotes; --report writes the result
	// to a file rather than making it survive a pipeline.
	const evaluate = argOf('eval')
		|| (argOf('eval-file') ? require('node:fs').readFileSync(argOf('eval-file'), 'utf8') : null);

	if (evaluate) {
		const result = await run(evaluate);
		const report = argOf('report');
		if (report) {
			require('node:fs').writeFileSync(report, JSON.stringify(result, null, 2));
			console.log(`[harness] report written to ${report}`);
		} else {
			console.log('[harness] eval', JSON.stringify(result));
		}
		await settle(1800);
	}


	const image = await window.webContents.capturePage();
	require('node:fs').writeFileSync(target, image.toPNG());
	console.log(`screenshot written to ${target}`);
	app.quit();
}

app.whenReady().then(async () => {
	registerHandlers();
	createWindow();

	try {
		const { port, token } = await backend.start();
		api = new Api(port, token);
		ready = true;

		stopEvents = api.events((event) => {
			if (window && !window.isDestroyed()) {
				window.webContents.send('job:event', event);
			}
		});

		if (window && !window.isDestroyed()) {
			window.webContents.send('backend:ready');
		}

		const shot = process.argv.find((arg) => arg.startsWith('--screenshot='));
		if (shot) {
			await screenshotAndQuit(shot.slice('--screenshot='.length));
		}
	} catch (error) {
		dialog.showErrorBox('Loadout could not start', error.message);
		app.quit();
	}
});

// The jar is this application's actual process; leaving it running after the window is
// gone would leave an HTTP server listening with nothing driving it.
app.on('window-all-closed', () => app.quit());

app.on('before-quit', () => {
	if (stopEvents) {
		stopEvents();
	}
	backend.stop();
});
