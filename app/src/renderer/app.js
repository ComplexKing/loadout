'use strict';

/*
 * Renderer logic.
 *
 * One rule runs through all of it: every string that came from a mod registry is written
 * with textContent, never innerHTML. Titles, descriptions and author names are typed by
 * strangers and served to us verbatim, so treating them as markup would be an injection
 * with extra steps -- and the page holds no API token precisely because it renders them.
 * The content security policy is the backstop; this is the actual defence.
 */

const api = window.loadout;

const state = {
	profiles: [],
	selected: null,
	profile: null,
	tab: 'mods',
	job: null,
	plan: null,
	searchToken: 0,
};

/* -- small helpers ------------------------------------------------------------------ */

const $ = (id) => document.getElementById(id);

/** Builds an element, with text set safely and never parsed as markup. */
function el(tag, className, text) {
	const node = document.createElement(tag);
	if (className) {
		node.className = className;
	}
	if (text !== undefined && text !== null) {
		node.textContent = String(text);
	}
	return node;
}

function clear(node) {
	while (node.firstChild) {
		node.removeChild(node.firstChild);
	}
}

let toastTimer = null;
function toast(message, isError) {
	const node = $('toast');
	node.textContent = message;
	node.classList.toggle('error', Boolean(isError));
	node.hidden = false;

	clearTimeout(toastTimer);
	toastTimer = setTimeout(() => { node.hidden = true; }, isError ? 6000 : 3000);
}

/**
 * Unwraps the {ok, data} envelope every bridge call returns.
 *
 * Failures surface as a toast and a null return, so callers branch on the value rather
 * than wrapping everything in try/catch. A launcher has a lot of operations that are
 * allowed to fail -- offline, no build for that version, a key that stopped working --
 * and none of them should be exceptional.
 */
async function call(promise, context) {
	const result = await promise;
	if (!result.ok) {
		toast(context ? `${context}: ${result.error}` : result.error, true);
		return null;
	}
	return result.data;
}

/* -- profiles ------------------------------------------------------------------------ */

async function loadProfiles() {
	const data = await call(api.profiles.list(), 'Could not load profiles');
	if (!data) {
		return;
	}

	state.profiles = data.profiles;
	renderProfileList();

	if (state.profiles.length === 0) {
		state.selected = null;
		$('empty-state').hidden = false;
		$('profile-view').hidden = true;
		return;
	}

	// Keep the selection across a refresh where possible; a rename or delete falls back
	// to the first profile rather than leaving nothing selected.
	const stillThere = state.profiles.some((p) => p.name === state.selected);
	await selectProfile(stillThere ? state.selected : state.profiles[0].name);
}

function renderProfileList() {
	const list = $('profile-list');
	clear(list);

	for (const profile of state.profiles) {
		const item = el('li', 'profile-item');
		if (profile.name === state.selected) {
			item.classList.add('selected');
		}

		item.appendChild(el('span', 'name', profile.name));
		item.appendChild(el('span', 'sub',
			`${profile.minecraftVersion} · ${profile.loader} · ${profile.modCount} mods`));

		item.addEventListener('click', () => selectProfile(profile.name));
		list.appendChild(item);
	}
}

async function selectProfile(name) {
	state.selected = name;
	$('empty-state').hidden = true;
	$('profile-view').hidden = false;
	renderProfileList();

	const profile = await call(api.profiles.get(name), 'Could not open profile');
	if (!profile) {
		return;
	}

	state.profile = profile;
	$('profile-name').textContent = profile.name;
	$('profile-meta').textContent =
		`Minecraft ${profile.minecraftVersion} · ${profile.loader} · ${profile.mods.length} mods`;

	renderMods();
	if (state.tab === 'versions') {
		renderSnapshots();
	}
}

/* -- mod list ------------------------------------------------------------------------ */

function renderMods() {
	const list = $('mod-list');
	clear(list);

	if (!state.profile || state.profile.mods.length === 0) {
		list.appendChild(el('div', 'empty-list',
			'No mods yet. Use the Browse tab to add some.'));
		return;
	}

	for (const mod of state.profile.mods) {
		list.appendChild(installedCard(mod));
	}
}

function installedCard(mod) {
	const card = el('div', 'mod-card');
	if (!mod.enabled) {
		card.classList.add('disabled');
	}

	card.appendChild(iconFor(null, mod.modId || mod.fileName));

	const body = el('div', 'mod-body');
	const titleRow = el('div', 'mod-title-row');
	titleRow.appendChild(el('span', 'mod-name', mod.modId || mod.fileName));
	body.appendChild(titleRow);
	body.appendChild(el('div', 'mod-desc', mod.fileName));

	const meta = el('div', 'mod-meta');
	if (mod.source) {
		meta.appendChild(el('span', `pill ${mod.source}`, mod.source));
	}
	if (mod.versionNumber) {
		meta.appendChild(el('span', 'pill', mod.versionNumber));
	}
	if (!mod.enabled) {
		meta.appendChild(el('span', 'pill', 'disabled'));
	}
	body.appendChild(meta);
	card.appendChild(body);

	const actions = el('div', 'mod-actions');

	const toggle = el('button', 'ghost-button small', mod.enabled ? 'Disable' : 'Enable');
	toggle.addEventListener('click', async () => {
		toggle.disabled = true;
		const done = await call(
			api.mods.toggle(state.selected, mod.fileName, !mod.enabled), 'Could not toggle');
		if (done) {
			await selectProfile(state.selected);
		}
		toggle.disabled = false;
	});

	const remove = el('button', 'ghost-button small danger', 'Remove');
	remove.addEventListener('click', async () => {
		const done = await call(api.mods.remove(state.selected, mod.fileName), 'Could not remove');
		if (done) {
			toast(`Removed ${mod.fileName}`);
			await selectProfile(state.selected);
			await loadProfiles();
		}
	});

	actions.appendChild(toggle);
	actions.appendChild(remove);
	card.appendChild(actions);
	return card;
}

/**
 * A mod's icon, or its initial when there is none.
 *
 * Registry icons are remote URLs, so a broken or slow one must not leave a gap in the
 * layout -- the fallback swaps in on error rather than being decided up front.
 */
function iconFor(url, name) {
	const initial = (name || '?').replace(/^\W+/, '').charAt(0).toUpperCase() || '?';

	if (!url) {
		const placeholder = el('div', 'mod-icon placeholder', initial);
		return placeholder;
	}

	const image = document.createElement('img');
	image.className = 'mod-icon';
	image.loading = 'lazy';
	image.alt = '';
	image.src = url;
	image.addEventListener('error', () => {
		image.replaceWith(el('div', 'mod-icon placeholder', initial));
	});
	return image;
}

/* -- browse -------------------------------------------------------------------------- */

let searchTimer = null;

function scheduleSearch() {
	clearTimeout(searchTimer);
	searchTimer = setTimeout(runSearch, 250);
}

async function runSearch() {
	if (!state.selected) {
		return;
	}

	// Every search carries a token; a slower earlier request that lands after a newer one
	// is discarded rather than overwriting fresher results.
	const token = ++state.searchToken;

	const results = $('search-results');
	const data = await call(api.search({
		q: $('search-input').value.trim(),
		profile: state.selected,
		sort: $('search-sort').value,
		limit: 40,
	}), 'Search failed');

	if (!data || token !== state.searchToken) {
		return;
	}

	const notes = $('search-notes');
	clear(notes);
	for (const note of data.notes || []) {
		notes.appendChild(el('div', 'note', note));
	}

	clear(results);
	if (data.results.length === 0) {
		results.appendChild(el('div', 'empty-list', 'Nothing matched.'));
		return;
	}

	const installed = new Set((state.profile?.mods || [])
		.filter((m) => m.projectId)
		.map((m) => `${m.source}:${m.projectId}`));

	for (const mod of data.results) {
		results.appendChild(searchCard(mod, installed.has(`${mod.source}:${mod.id}`)));
	}
}

function searchCard(mod, alreadyInstalled) {
	const card = el('div', 'mod-card');
	card.appendChild(iconFor(mod.iconUrl, mod.title));

	const body = el('div', 'mod-body');

	const titleRow = el('div', 'mod-title-row');
	titleRow.appendChild(el('span', 'mod-name', mod.title));
	if (mod.author) {
		titleRow.appendChild(el('span', 'mod-author', `by ${mod.author}`));
	}
	body.appendChild(titleRow);

	if (mod.description) {
		body.appendChild(el('div', 'mod-desc', mod.description));
	}

	const meta = el('div', 'mod-meta');
	meta.appendChild(el('span', `pill ${mod.source}`, mod.source));
	meta.appendChild(el('span', 'pill', `${mod.downloadsShort} downloads`));
	body.appendChild(meta);
	card.appendChild(body);

	const actions = el('div', 'mod-actions');

	if (mod.webUrl) {
		const open = el('button', 'ghost-button small', 'Page');
		open.addEventListener('click', () => api.openExternal(mod.webUrl));
		actions.appendChild(open);
	}

	const install = el('button', 'primary-button', alreadyInstalled ? 'Installed' : 'Install');
	install.disabled = alreadyInstalled;
	install.addEventListener('click', async () => {
		install.disabled = true;
		install.textContent = 'Installing';
		const started = await call(
			api.mods.install(state.selected, mod.source, mod.id), 'Install failed');
		if (!started) {
			install.disabled = false;
			install.textContent = 'Install';
		}
		// Success is reported by the job event, which also refreshes the list.
	});
	actions.appendChild(install);

	card.appendChild(actions);
	return card;
}

/* -- versions and history ------------------------------------------------------------ */

async function renderSnapshots() {
	const list = $('snapshot-list');
	clear(list);

	const data = await call(api.snapshots.list(state.selected), 'Could not load history');
	if (!data) {
		return;
	}

	if (data.snapshots.length === 0) {
		list.appendChild(el('div', 'empty-list', 'No history yet.'));
		return;
	}

	for (const snapshot of data.snapshots) {
		const row = el('div', 'snapshot');

		const left = el('div');
		left.appendChild(el('div', 'reason', snapshot.reason));
		left.appendChild(el('div', 'muted',
			`${new Date(snapshot.takenAt).toLocaleString()} · Minecraft `
			+ `${snapshot.minecraftVersion} · ${snapshot.modCount} mods`));
		row.appendChild(left);

		const restore = el('button', 'ghost-button small', 'Restore');
		restore.addEventListener('click', async () => {
			restore.disabled = true;
			const done = await call(
				api.snapshots.rollback(state.selected, snapshot.id), 'Rollback failed');
			if (done) {
				toast(`Restored to ${snapshot.reason}`);
				await selectProfile(state.selected);
				await loadProfiles();
				await renderSnapshots();
			}
			restore.disabled = false;
		});
		row.appendChild(restore);

		list.appendChild(row);
	}
}

function renderPlan(plan) {
	const box = $('migrate-result');
	clear(box);

	const summary = el('div', 'muted',
		plan.clean
			? 'Every mod has a build for this version.'
			: 'Some mods have no build for this version.');
	summary.style.marginBottom = '10px';
	box.appendChild(summary);

	for (const entry of plan.entries) {
		const row = el('div', 'plan-entry');

		const left = el('div');
		left.appendChild(el('div', null, entry.name));
		if (entry.targetFileName) {
			left.appendChild(el('div', 'muted', `→ ${entry.targetFileName}`));
		}
		row.appendChild(left);

		row.appendChild(el('span', `status ${entry.status}`, entry.status.replace(/_/g, ' ')));
		box.appendChild(row);
	}

	// Applying is only offered once a plan exists, so nobody migrates without having been
	// shown what it would do.
	$('migrate-apply').disabled = false;
}

/* -- jobs ---------------------------------------------------------------------------- */

function onJobEvent(event) {
	const job = event.job;

	if (job.state === 'running') {
		state.job = job;
		showJob(job);
		return;
	}

	// Finished, one way or another.
	if (state.job && state.job.id === job.id) {
		state.job = null;
		$('jobbar').hidden = true;
	}

	if (job.state === 'failed') {
		toast(`${job.kind} failed: ${job.error}`, true);
	} else if (job.state === 'cancelled') {
		toast(`${job.kind} cancelled`);
	} else {
		reportSuccess(job);
	}

	// Anything that finished may have changed the profile on disk.
	if (state.selected) {
		selectProfile(state.selected).then(loadProfiles);
	}
}

function reportSuccess(job) {
	const result = job.result || {};

	if (job.kind === 'install') {
		const added = (result.installed || []).length + (result.upgraded || []).length;
		const blocked = result.blocked || [];

		if (blocked.length > 0) {
			// Not a failure: the author has opted out of third-party downloads, and the
			// right response is to send people to the page rather than around it.
			toast(`${blocked.join(', ')} must be downloaded from its own page`, true);
		} else if (added > 0) {
			toast(`Installed ${added} file${added === 1 ? '' : 's'}`);
		} else if ((result.alreadyPresent || []).length > 0) {
			toast('Already installed');
		} else if ((result.unavailable || []).length > 0) {
			toast(`No build for this version: ${result.unavailable.join(', ')}`, true);
		}
		return;
	}

	if (job.kind === 'migrate') {
		if (result.applied) {
			toast(`Migrated — ${result.modsChanged} mods changed`);
		} else if (result.plan) {
			state.plan = result.plan;
			renderPlan(result.plan);
		}
		return;
	}

	if (job.kind === 'launch') {
		toast(`Minecraft exited with code ${result.exitCode}`);
	}
}

function showJob(job) {
	$('jobbar').hidden = false;
	$('job-title').textContent = `${job.kind}: ${job.subject}`;
	$('job-stage').textContent = job.stage || '';

	const fill = $('job-fill');
	if (job.total > 0) {
		fill.classList.remove('indeterminate');
		fill.style.width = `${Math.round((job.done / job.total) * 100)}%`;
	} else {
		// No countable total -- fetching metadata, or a running game. Still has to look
		// like something is happening.
		fill.classList.add('indeterminate');
	}
}

/* -- settings ------------------------------------------------------------------------ */

async function renderSources() {
	const box = $('source-status');
	clear(box);
	box.appendChild(el('div', 'muted', 'Checking…'));

	// Verified, not merely configured: this dialog exists to explain why a source is not
	// working, and "a key is present" does not answer that.
	const data = await call(api.sources.list(true));
	clear(box);
	if (!data) {
		return;
	}

	for (const source of data.sources) {
		const row = el('div', 'source-row');
		const dot = el('span', 'dot');
		dot.classList.add(source.available ? 'on' : (source.configured ? 'bad' : 'off'));
		row.appendChild(dot);
		row.appendChild(el('span', null, source.name));
		row.appendChild(el('span', 'muted',
			source.available ? 'ready' : (source.configured ? 'not working' : 'not set up')));
		box.appendChild(row);

		if (source.reason) {
			box.appendChild(el('div', 'source-reason', source.reason));
		}
	}
}

/* -- wiring -------------------------------------------------------------------------- */

function switchTab(name) {
	state.tab = name;

	for (const tab of document.querySelectorAll('.tab')) {
		tab.classList.toggle('active', tab.dataset.tab === name);
	}
	for (const panel of document.querySelectorAll('.tab-panel')) {
		panel.hidden = panel.dataset.panel !== name;
	}

	if (name === 'browse' && $('search-results').childElementCount === 0) {
		runSearch();
	}
	if (name === 'versions') {
		renderSnapshots();
	}
}

function wire() {
	for (const tab of document.querySelectorAll('.tab')) {
		tab.addEventListener('click', () => switchTab(tab.dataset.tab));
	}

	$('search-input').addEventListener('input', scheduleSearch);
	$('search-sort').addEventListener('change', runSearch);

	const openCreate = () => {
		$('np-name').value = '';
		$('np-version').value = '';
		$('profile-dialog').showModal();
	};
	$('new-profile').addEventListener('click', openCreate);
	$('empty-create').addEventListener('click', openCreate);

	$('profile-dialog').addEventListener('close', async (event) => {
		if (event.target.returnValue !== 'create') {
			return;
		}

		const created = await call(api.profiles.create({
			name: $('np-name').value.trim(),
			minecraftVersion: $('np-version').value.trim(),
			loader: $('np-loader').value,
		}), 'Could not create profile');

		if (created) {
			state.selected = created.name;
			toast(`Created ${created.name}`);
			await loadProfiles();
		}
	});

	$('open-settings').addEventListener('click', () => {
		$('cf-key').value = '';
		$('settings-dialog').showModal();
		renderSources();
	});

	$('settings-dialog').addEventListener('close', async (event) => {
		if (event.target.returnValue !== 'save') {
			return;
		}
		const key = $('cf-key').value.trim();
		if (!key) {
			return;
		}

		const saved = await call(api.sources.setCurseForgeKey(key), 'Key rejected');
		// Cleared either way: a rejected key should not sit in the field waiting to be
		// resubmitted, and a saved one has no reason to stay on screen.
		$('cf-key').value = '';
		if (saved) {
			toast('CurseForge key saved');
		}
	});

	$('launch').addEventListener('click', async () => {
		const started = await call(api.launch(state.selected, null), 'Could not launch');
		if (started) {
			toast('Starting Minecraft…');
		}
	});

	$('migrate-plan').addEventListener('click', async () => {
		const target = $('migrate-target').value.trim();
		if (!target) {
			toast('Enter a Minecraft version first', true);
			return;
		}
		$('migrate-apply').disabled = true;
		await call(api.migrate(state.selected, target, false, false), 'Could not plan');
	});

	$('migrate-apply').addEventListener('click', async () => {
		const target = $('migrate-target').value.trim();
		await call(api.migrate(state.selected, target, true, false), 'Migration failed');
	});

	$('job-cancel').addEventListener('click', () => {
		if (state.job) {
			api.jobs.cancel(state.job.id);
		}
	});

	api.onJobEvent(onJobEvent);
	api.onReady(() => loadProfiles());
}

wire();

// The backend may already be up if this window reloaded, in which case the ready event
// has been and gone. A direct call settles which it is.
api.health().then((result) => {
	if (result.ok) {
		loadProfiles();
	}
});
