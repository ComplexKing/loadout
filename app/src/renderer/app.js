'use strict';

/*
 * Renderer.
 *
 * One rule runs through all of it: every string that came from a mod registry is written
 * with textContent, never innerHTML. Titles, descriptions and author names are typed by
 * strangers and served verbatim, so treating them as markup would be an injection with
 * extra steps -- and this page holds no API token precisely because it renders them. The
 * content security policy is the backstop; this is the actual defence.
 */

const api = window.loadout;

const state = {
	view: 'home',
	instances: [],
	current: null,       // full profile of the open instance
	tab: 'mods',
	job: null,
	modFilter: '',
	searchSeq: 0,
	icons: {},          // installed mod artwork, by file name
};

/* -- helpers -------------------------------------------------------------------------- */

const $ = (id) => document.getElementById(id);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));

function el(tag, className, text) {
	const node = document.createElement(tag);
	if (className) node.className = className;
	if (text !== undefined && text !== null) node.textContent = String(text);
	return node;
}

function icon(name, className) {
	const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
	svg.setAttribute('class', className || 'ico');
	const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
	use.setAttribute('href', `#${name}`);
	svg.appendChild(use);
	return svg;
}

function clear(node) {
	while (node.firstChild) node.removeChild(node.firstChild);
}

let toastTimer = null;
function toast(message, isError) {
	const node = $('toast');
	node.textContent = message;
	node.classList.toggle('error', Boolean(isError));
	node.hidden = false;
	clearTimeout(toastTimer);
	toastTimer = setTimeout(() => { node.hidden = true; }, isError ? 7000 : 3200);
}

/** Unwraps the {ok, data} envelope, reporting failures rather than throwing. */
async function call(promise, context) {
	const result = await promise;
	if (!result.ok) {
		toast(context ? `${context}: ${result.error}` : result.error, true);
		return null;
	}
	return result.data;
}

/**
 * A stable colour per name.
 *
 * Instances rarely have artwork, and a grid of identical grey squares is unreadable at a
 * glance -- which is the whole reason for showing tiles instead of a list. Deriving the
 * hue from the name means the same instance is the same colour every launch, so people
 * navigate by colour without being told to.
 */
function avatarFor(name) {
	let hash = 0;
	for (let i = 0; i < name.length; i++) {
		hash = (hash * 31 + name.charCodeAt(i)) >>> 0;
	}
	const hue = hash % 360;
	// Two stops 40 degrees apart, kept dark enough that white text stays readable on any hue.
	return {
		background: `linear-gradient(135deg, hsl(${hue} 52% 42%), hsl(${(hue + 40) % 360} 54% 32%))`,
		initial: (name.trim()[0] || '?').toUpperCase(),
	};
}

function avatarNode(name, className) {
	const { background, initial } = avatarFor(name);
	const node = el('div', className, initial);
	node.style.background = background;
	return node;
}

/**
 * Artwork for a mod, falling back to a lettered tile.
 *
 * The fallback swaps in on error rather than being chosen up front, because whether a
 * remote icon loads is not knowable until it does or does not.
 */
function artFor(url, name, className) {
	const cls = className || 'art';
	const fallback = () => {
		const { background, initial } = avatarFor(name || '?');
		const node = el('div', cls, initial);
		node.style.background = background;
		return node;
	};

	if (!url) return fallback();

	const image = document.createElement('img');
	image.className = cls;
	image.alt = '';
	image.loading = 'lazy';
	image.src = url;
	image.addEventListener('error', () => image.replaceWith(fallback()));
	return image;
}

/* -- routing -------------------------------------------------------------------------- */

function show(view) {
	state.view = view;

	for (const section of $$('.view')) {
		section.hidden = section.dataset.view !== view;
	}
	for (const button of $$('.rail-btn[data-view]')) {
		button.classList.toggle('active', button.dataset.view === view);
	}
	for (const tile of $$('.rail-tile')) {
		tile.classList.toggle('active',
			view === 'instance' && state.current && tile.dataset.name === state.current.name);
	}

	if (view === 'settings') {
		renderSources();
		renderJavas();
	}
	if (view === 'browse') {
		syncBrowseTargets();
		if ($('browse-results').childElementCount === 0) runBrowse();
	}
}

/* -- instances ------------------------------------------------------------------------ */

async function loadInstances() {
	const data = await call(api.profiles.list(), 'Could not load instances');
	if (!data) return;

	state.instances = data.profiles;
	renderRail();
	renderInstanceGrid();

	$('home-sub').textContent = state.instances.length === 1
		? '1 instance'
		: `${state.instances.length} instances`;
	$('home-empty').hidden = state.instances.length > 0;

	// A refresh after a delete must not leave a stale instance page on screen.
	if (state.current && !state.instances.some((i) => i.name === state.current.name)) {
		state.current = null;
		show('home');
	}
}

function renderRail() {
	const rail = $('rail-instances');
	clear(rail);

	for (const instance of state.instances) {
		const tile = avatarNode(instance.name, 'rail-tile');
		tile.dataset.name = instance.name;
		tile.title = `${instance.name} — ${instance.minecraftVersion} ${instance.loader}`;
		tile.addEventListener('click', () => openInstance(instance.name));
		rail.appendChild(tile);
	}
}

function renderInstanceGrid() {
	const grid = $('instance-grid');
	clear(grid);

	for (const instance of state.instances) {
		const card = el('button', 'instance-card');
		card.appendChild(avatarNode(instance.name, 'instance-avatar'));

		const body = el('div');
		body.appendChild(el('div', 'instance-name', instance.name));
		body.appendChild(el('div', 'instance-sub',
			`${instance.minecraftVersion} · ${instance.loader}`));
		body.appendChild(el('div', 'instance-sub',
			`${instance.modCount} mods · ${instance.enabledCount} on`));
		card.appendChild(body);

		card.addEventListener('click', () => openInstance(instance.name));
		grid.appendChild(card);
	}
}

async function openInstance(name) {
	const profile = await call(api.profiles.get(name), 'Could not open instance');
	if (!profile) return;

	state.current = profile;
	state.modFilter = '';
	state.icons = {};
	$('mod-filter').value = '';

	const heroIcon = $('hero-icon');
	const { background, initial } = avatarFor(profile.name);
	heroIcon.style.background = background;
	heroIcon.textContent = initial;

	$('hero-name').textContent = profile.name;

	const meta = $('hero-meta');
	clear(meta);
	meta.appendChild(el('span', 'pill', `Minecraft ${profile.minecraftVersion}`));
	meta.appendChild(el('span', 'pill', profile.loader));
	meta.appendChild(el('span', 'pill', `${profile.mods.length} mods`));

	renderMods();
	show('instance');
	switchTab(state.tab === 'mods' ? 'mods' : state.tab);

	// Deliberately not awaited. Names and versions are already on screen; artwork needs two
	// registries and should never be what a mod list waits for.
	loadModIcons(profile.name);
}

/**
 * Fills in installed-mod artwork once the registries answer.
 *
 * Patches the existing images rather than re-rendering, so nothing moves under the cursor
 * and a row being hovered or mid-click is not replaced beneath it.
 */
async function loadModIcons(name) {
	const data = await call(api.mods.icons(name));
	if (!data || !state.current || state.current.name !== name) return;

	state.icons = data.icons || {};

	for (const node of $$('#mod-list .art[data-file]')) {
		const url = state.icons[node.dataset.file];
		if (!url) continue;

		const image = document.createElement('img');
		image.className = 'art';
		image.alt = '';
		image.loading = 'lazy';
		image.src = url;
		image.dataset.file = node.dataset.file;
		// If it fails to load, the lettered tile it replaced goes back.
		image.addEventListener('error', () => image.replaceWith(node));
		node.replaceWith(image);
	}
}

/* -- installed mods ------------------------------------------------------------------- */

function renderMods() {
	const list = $('mod-list');
	clear(list);
	if (!state.current) return;

	const filter = state.modFilter.toLowerCase();
	const mods = state.current.mods.filter((mod) =>
		!filter
		|| (mod.modId || '').toLowerCase().includes(filter)
		|| mod.fileName.toLowerCase().includes(filter));

	$('mod-count').textContent = filter
		? `${mods.length} of ${state.current.mods.length}`
		: `${state.current.mods.length} installed`;

	if (mods.length === 0) {
		const blank = el('div', 'blank small');
		const art = el('div', 'blank-art');
		art.appendChild(icon('i-folder', 'ico'));
		blank.appendChild(art);
		blank.appendChild(el('p', null, state.current.mods.length === 0
			? 'No mods yet. Use Add content to install some.'
			: 'Nothing matches that filter.'));
		list.appendChild(blank);
		return;
	}

	for (const mod of mods) list.appendChild(modRow(mod));
}

function modRow(mod) {
	const row = el('div', 'row');
	if (!mod.enabled) row.classList.add('off');

	const art = artFor(state.icons[mod.fileName], mod.modId || mod.fileName, 'art');
	art.dataset.file = mod.fileName;
	row.appendChild(art);

	const body = el('div', 'row-body');
	body.appendChild(el('div', 'row-title', mod.modId || mod.fileName));
	body.appendChild(el('div', 'row-sub', mod.fileName));
	row.appendChild(body);

	const actions = el('div', 'row-actions');
	if (mod.source) actions.appendChild(el('span', `pill ${mod.source}`, mod.source));
	if (!mod.enabled) actions.appendChild(el('span', 'pill off', 'off'));

	const toggle = el('button', 'btn quiet sm', mod.enabled ? 'Disable' : 'Enable');
	toggle.addEventListener('click', async () => {
		toggle.disabled = true;
		if (await call(api.mods.toggle(state.current.name, mod.fileName, !mod.enabled),
			'Could not toggle')) {
			await refreshCurrent();
		}
	});

	const remove = el('button', 'btn quiet sm danger-hover', 'Remove');
	remove.addEventListener('click', async () => {
		if (await call(api.mods.remove(state.current.name, mod.fileName), 'Could not remove')) {
			toast(`Removed ${mod.fileName}`);
			await refreshCurrent();
			await loadInstances();
		}
	});

	actions.appendChild(toggle);
	actions.appendChild(remove);
	row.appendChild(actions);
	return row;
}

async function refreshCurrent() {
	if (!state.current) return;
	const profile = await call(api.profiles.get(state.current.name));
	if (profile) {
		state.current = profile;
		renderMods();

		const meta = $('hero-meta');
		clear(meta);
		meta.appendChild(el('span', 'pill', `Minecraft ${profile.minecraftVersion}`));
		meta.appendChild(el('span', 'pill', profile.loader));
		meta.appendChild(el('span', 'pill', `${profile.mods.length} mods`));
	}
}

/* -- search --------------------------------------------------------------------------- */

const debouncers = new Map();
function debounce(key, fn, wait) {
	clearTimeout(debouncers.get(key));
	debouncers.set(key, setTimeout(fn, wait === undefined ? 260 : wait));
}

/**
 * Runs a search and renders it.
 *
 * Every call carries a sequence number so a slower earlier request that lands after a
 * newer one is dropped rather than overwriting fresher results -- the usual way a search
 * box ends up showing answers to a question you already finished typing over.
 */
async function search({ profileName, query, sort, source, resultsNode, notesNode }) {
	const seq = ++state.searchSeq;

	const data = await call(api.search({
		q: query, profile: profileName, sort, source, limit: 40,
	}));

	if (seq !== state.searchSeq) return;

	clear(notesNode);
	if (!data) {
		notesNode.appendChild(problemNotice(
			'Could not reach the mod registries.',
			() => search({ profileName, query, sort, source, resultsNode, notesNode })));
		return;
	}

	for (const note of data.notes || []) {
		notesNode.appendChild(problemNotice(note,
			() => search({ profileName, query, sort, source, resultsNode, notesNode })));
	}

	clear(resultsNode);

	if (data.results.length === 0) {
		const blank = el('div', 'blank small');
		const art = el('div', 'blank-art');
		art.appendChild(icon('i-search', 'ico'));
		blank.appendChild(art);
		blank.appendChild(el('p', null, query
			? `Nothing matched "${query}" for this version.`
			: 'No results.'));
		resultsNode.appendChild(blank);
		return;
	}

	const installed = new Set((state.current?.mods || [])
		.filter((m) => m.projectId)
		.map((m) => `${m.source}:${m.projectId}`));

	for (const mod of data.results) {
		resultsNode.appendChild(modCard(mod, installed.has(`${mod.source}:${mod.id}`), profileName));
	}
}

/**
 * A source failure, stated plainly with a way to try again.
 *
 * Registries go down, keys expire and laptops lose wifi, and none of those should read as
 * the application being broken. Saying which source failed and offering a retry is the
 * difference between a bug report and someone clicking a button.
 */
function problemNotice(text, onRetry) {
	const notice = el('div', 'notice warn');
	notice.appendChild(icon('i-warn', 'ico sm'));
	notice.appendChild(el('span', null, text));

	if (onRetry) {
		const retry = el('button', 'btn quiet sm', 'Retry');
		retry.addEventListener('click', onRetry);
		notice.appendChild(retry);
	}
	return notice;
}

function modCard(mod, alreadyInstalled, profileName) {
	const card = el('div', 'card');
	card.appendChild(artFor(mod.iconUrl, mod.title, 'art lg'));

	const body = el('div', 'card-body');
	body.appendChild(el('div', 'card-title', mod.title));
	if (mod.author) body.appendChild(el('div', 'card-by', `by ${mod.author}`));
	body.appendChild(el('div', 'card-desc', mod.description || ''));

	// Meta and actions are separate groups so the buttons stay on the same line as the
	// pills. Letting one flex row wrap put Install on a line of its own, which also made
	// every card in the row a different height.
	const foot = el('div', 'card-foot');
	const meta = el('div', 'card-meta');
	meta.appendChild(el('span', `pill ${mod.source}`, mod.source));
	meta.appendChild(el('span', 'pill', mod.downloadsShort));
	foot.appendChild(meta);

	const actions = el('div', 'card-actions');
	if (mod.webUrl) {
		const page = el('button', 'btn quiet sm', 'Page');
		page.addEventListener('click', () => api.openExternal(mod.webUrl));
		actions.appendChild(page);
	}

	// A split control: the button installs the newest build, the chevron opens the
	// rest. Making people choose a version every time would be tedious, and never
	// letting them is the limitation this fixes -- a beta sitting above the last stable
	// release is common in Minecraft modding, and stepping back has to be possible.
	const group = el('div', 'split');

	const install = el('button', 'btn primary sm', alreadyInstalled ? 'Installed' : 'Install');
	install.disabled = alreadyInstalled || !profileName;
	install.addEventListener('click', async () => {
		install.disabled = true;
		install.textContent = 'Installing…';
		const started = await call(api.mods.install(profileName, mod.source, mod.id),
			'Install failed');
		if (!started) {
			install.disabled = false;
			install.textContent = 'Install';
		}
		// Success arrives as a job event, which refreshes the list.
	});
	group.appendChild(install);

	if (profileName) {
		const more = el('button', 'btn primary sm split-more');
		more.appendChild(icon('i-chevron', 'ico sm'));
		more.title = 'Choose a version';
		more.addEventListener('click', () => openVersions(mod, profileName));
		group.appendChild(more);
	}

	actions.appendChild(group);
	foot.appendChild(actions);

	body.appendChild(foot);
	card.appendChild(body);
	return card;
}

function runAddSearch() {
	if (!state.current) return;
	search({
		profileName: state.current.name,
		query: $('add-search').value.trim(),
		sort: $('add-sort').value,
		source: $('add-source').value,
		resultsNode: $('add-results'),
		notesNode: $('add-notes'),
	});
}

function runBrowse() {
	search({
		profileName: $('browse-target').value || undefined,
		query: $('browse-search').value.trim(),
		sort: $('browse-sort').value,
		source: $('browse-source').value,
		resultsNode: $('browse-results'),
		notesNode: $('browse-notes'),
	});
}

function syncBrowseTargets() {
	const select = $('browse-target');
	const previous = select.value;
	clear(select);

	if (state.instances.length === 0) {
		select.appendChild(el('option', null, 'No instances'));
		select.disabled = true;
		return;
	}

	select.disabled = false;
	for (const instance of state.instances) {
		const option = el('option', null,
			`${instance.name} · ${instance.minecraftVersion}`);
		option.value = instance.name;
		select.appendChild(option);
	}

	// Prefer what was already chosen, then the open instance, then the first.
	select.value = state.instances.some((i) => i.name === previous)
		? previous
		: (state.current?.name || state.instances[0].name);
}

/**
 * Lists every build of a mod that fits, and installs the chosen one.
 *
 * Scoped to the profile, so what is offered is what will actually run here rather than
 * everything the registry has ever published.
 */
async function openVersions(mod, profileName) {
	const dialog = $('versions-dialog');
	const list = $('vd-list');

	$('vd-title').textContent = mod.title;
	$('vd-sub').textContent = 'Loading builds\u2026';
	clear(list);
	dialog.showModal();

	const data = await call(api.versions(mod.source, mod.id, profileName));
	if (!data) {
		$('vd-sub').textContent = 'Could not load versions.';
		return;
	}

	const instance = state.instances.find((i) => i.name === profileName);
	$('vd-sub').textContent = data.versions.length === 0
		? 'No builds for this Minecraft version.'
		: data.versions.length + ' builds for '
			+ (instance ? instance.minecraftVersion : 'this instance');

	data.versions.forEach((version, index) => {
		const row = el('div', 'row');
		row.style.gridTemplateColumns = 'minmax(0,1fr) auto';

		const body = el('div', 'row-body');
		body.appendChild(el('div', 'row-title', version.versionNumber || version.fileName));
		body.appendChild(el('div', 'row-sub',
			version.fileName + ' \u00b7 ' + (version.fileSize / 1048576).toFixed(2) + ' MB'));
		row.appendChild(body);

		const actions = el('div', 'row-actions');

		// The first entry is what a plain Install would have picked, and saying so saves
		// anyone comparing version strings to work out which that was.
		if (index === 0) {
			actions.appendChild(el('span', 'pill ok', 'latest'));
		}

		if (!version.downloadable) {
			actions.appendChild(el('span', 'pill off', 'not downloadable'));
		} else {
			const pick = el('button', 'btn sm', 'Install');
			pick.addEventListener('click', async () => {
				pick.disabled = true;
				const started = await call(
					api.mods.installVersion(profileName, mod.source, mod.id, version.versionId),
					'Install failed');
				if (started) {
					dialog.close();
				} else {
					pick.disabled = false;
				}
			});
			actions.appendChild(pick);
		}

		row.appendChild(actions);
		list.appendChild(row);
	});
}

/* -- versions ------------------------------------------------------------------------- */

async function renderSnapshots() {
	const list = $('snapshots');
	clear(list);
	if (!state.current) return;

	const data = await call(api.snapshots.list(state.current.name), 'Could not load history');
	if (!data) return;

	if (data.snapshots.length === 0) {
		list.appendChild(el('p', 'sub', 'Nothing yet. A snapshot is taken before every change.'));
		return;
	}

	for (const snapshot of data.snapshots) {
		const row = el('div', 'row');
		row.style.gridTemplateColumns = 'minmax(0,1fr) auto';

		const body = el('div', 'row-body');
		body.appendChild(el('div', 'row-title', snapshot.reason));
		body.appendChild(el('div', 'row-sub',
			`${new Date(snapshot.takenAt).toLocaleString()} · Minecraft ${snapshot.minecraftVersion}`
			+ ` · ${snapshot.modCount} mods`));
		row.appendChild(body);

		const restore = el('button', 'btn sm', 'Restore');
		restore.addEventListener('click', async () => {
			restore.disabled = true;
			if (await call(api.snapshots.rollback(state.current.name, snapshot.id),
				'Rollback failed')) {
				toast(`Restored: ${snapshot.reason}`);
				await refreshCurrent();
				await loadInstances();
				await renderSnapshots();
			}
			restore.disabled = false;
		});
		row.appendChild(restore);
		list.appendChild(row);
	}
}

function renderPlan(plan) {
	const box = $('mig-result');
	clear(box);

	box.appendChild(el('p', 'sub', plan.clean
		? 'Every mod has a build for this version.'
		: 'Some mods have no build for this version. Applying will leave them behind.'));

	for (const entry of plan.entries) {
		const row = el('div', 'plan-row');

		const left = el('div');
		left.appendChild(el('div', null, entry.name));
		if (entry.targetFileName) left.appendChild(el('div', 'row-sub', `→ ${entry.targetFileName}`));
		row.appendChild(left);
		row.appendChild(el('span', `status ${entry.status}`, entry.status.replace(/_/g, ' ')));
		box.appendChild(row);
	}

	$('mig-apply').disabled = false;
}

/* -- settings ------------------------------------------------------------------------- */

async function renderSources() {
	const box = $('sources');
	clear(box);
	box.appendChild(el('p', 'sub', 'Checking…'));

	// Verified, not merely configured: this screen exists to explain why a source is not
	// working, and "a key is present" does not answer that question.
	const data = await call(api.sources.list(true));
	clear(box);
	if (!data) return;

	for (const source of data.sources) {
		const row = el('div', 'row');
		row.style.gridTemplateColumns = 'minmax(0,1fr) auto';

		const body = el('div', 'row-body');
		body.appendChild(el('div', 'row-title', source.name));
		body.appendChild(el('div', 'row-sub', source.reason
			|| (source.available ? 'Working' : 'Not set up')));
		row.appendChild(body);

		row.appendChild(el('span', `pill ${source.available ? 'ok' : 'off'}`,
			source.available ? 'ready' : (source.configured ? 'error' : 'off')));
		box.appendChild(row);
	}
}

async function renderJavas() {
	const box = $('javas');
	clear(box);

	const data = await call(api.java());
	if (!data) return;

	if (data.installs.length === 0) {
		box.appendChild(el('p', 'sub', 'No Java found. Minecraft 1.20.5+ needs Java 21 or newer.'));
		return;
	}

	for (const install of data.installs) {
		const row = el('div', 'row');
		row.style.gridTemplateColumns = 'minmax(0,1fr) auto';

		const body = el('div', 'row-body');
		body.appendChild(el('div', 'row-title', `Java ${install.majorVersion}`));
		body.appendChild(el('div', 'row-sub', install.path));
		row.appendChild(body);
		box.appendChild(row);
	}
}

/* -- jobs ----------------------------------------------------------------------------- */

function onJobEvent(event) {
	const job = event.job;

	if (job.state === 'running') {
		state.job = job;
		$('jobbar').hidden = false;
		$('job-title').textContent = `${job.kind}: ${job.subject}`;
		$('job-stage').textContent = job.stage || '';

		const fill = $('job-fill');
		if (job.total > 0) {
			fill.classList.remove('pending');
			fill.style.width = `${Math.round((job.done / job.total) * 100)}%`;
		} else {
			fill.classList.add('pending');
		}
		return;
	}

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

	if (state.current) refreshCurrent();
	loadInstances();
}

function reportSuccess(job) {
	const result = job.result || {};

	if (job.kind === 'install') {
		const added = (result.installed || []).length + (result.upgraded || []).length;

		if ((result.blocked || []).length > 0) {
			// Not a failure: the author has opted out of third-party downloads, and the
			// right response is to send people to the page rather than around it.
			toast(`${result.blocked.join(', ')} must be downloaded from its own page`, true);
		} else if (added > 0) {
			toast(`Installed ${added} file${added === 1 ? '' : 's'}`);
			// Buttons in the results list say "Install" until the list is rebuilt.
			if (state.view === 'instance') runAddSearch(); else runBrowse();
		} else if ((result.alreadyPresent || []).length > 0) {
			toast('Already installed');
		} else if ((result.unavailable || []).length > 0) {
			toast(`No build for this version: ${result.unavailable.join(', ')}`, true);
		}
		return;
	}

	if (job.kind === 'migrate') {
		if (result.applied) {
			toast(`Moved to ${result.plan?.targetGameVersion ?? 'the new version'} — `
				+ `${result.modsChanged} mods changed`);
			renderSnapshots();
		} else if (result.plan) {
			renderPlan(result.plan);
		}
		return;
	}

	if (job.kind === 'launch') {
		toast(`Minecraft exited with code ${result.exitCode}`);
	}
}

/* -- tabs and wiring ------------------------------------------------------------------ */

function switchTab(name) {
	state.tab = name;

	for (const seg of $$('.seg')) seg.classList.toggle('active', seg.dataset.tab === name);
	for (const panel of $$('.panel')) panel.hidden = panel.dataset.panel !== name;

	if (name === 'add' && $('add-results').childElementCount === 0) runAddSearch();
	if (name === 'versions') renderSnapshots();
}

function confirmDelete(name) {
	$('confirm-title').textContent = `Delete "${name}"?`;
	$('confirm-body').textContent =
		'The mod files stay in the shared store and the history is kept, so this can be '
		+ 'undone until you prune.';
	$('confirm-dialog').showModal();
}

function wire() {
	for (const button of $$('.rail-btn[data-view]')) {
		button.addEventListener('click', () => show(button.dataset.view));
	}
	for (const seg of $$('.seg')) {
		seg.addEventListener('click', () => switchTab(seg.dataset.tab));
	}

	const openNew = () => {
		$('nd-name').value = '';
		$('nd-version').value = '';
		$('new-dialog').showModal();
	};
	$('rail-new').addEventListener('click', openNew);
	$('home-new').addEventListener('click', openNew);
	$('blank-new').addEventListener('click', openNew);

	$('new-dialog').addEventListener('close', async (event) => {
		if (event.target.returnValue !== 'create') return;

		const created = await call(api.profiles.create({
			name: $('nd-name').value.trim(),
			minecraftVersion: $('nd-version').value.trim(),
			loader: $('nd-loader').value,
		}), 'Could not create instance');

		if (created) {
			toast(`Created ${created.name}`);
			await loadInstances();
			await openInstance(created.name);
		}
	});

	$('instance-delete').addEventListener('click', () => confirmDelete(state.current.name));

	$('confirm-dialog').addEventListener('close', async (event) => {
		if (event.target.returnValue !== 'ok' || !state.current) return;

		const name = state.current.name;
		if (await call(api.profiles.remove(name), 'Could not delete')) {
			toast(`Deleted ${name}`);
			state.current = null;
			await loadInstances();
			show('home');
		}
	});

	$('mod-filter').addEventListener('input', (event) => {
		state.modFilter = event.target.value.trim();
		debounce('filter', renderMods, 120);
	});

	$('add-search').addEventListener('input', () => debounce('add', runAddSearch));
	$('add-sort').addEventListener('change', runAddSearch);

	$('browse-search').addEventListener('input', () => debounce('browse', runBrowse));
	$('browse-sort').addEventListener('change', runBrowse);
	$('browse-target').addEventListener('change', runBrowse);

	$('mig-check').addEventListener('click', async () => {
		const target = $('mig-target').value.trim();
		if (!target) return toast('Enter a Minecraft version first', true);
		$('mig-apply').disabled = true;
		await call(api.migrate(state.current.name, target, false, false), 'Could not check');
	});

	$('mig-apply').addEventListener('click', async () => {
		await call(api.migrate(state.current.name, $('mig-target').value.trim(), true, false),
			'Migration failed');
	});

	$('play').addEventListener('click', async () => {
		if (await call(api.launch(state.current.name, null), 'Could not launch')) {
			toast('Starting Minecraft…');
		}
	});

	$('vd-close').addEventListener('click', () => $('versions-dialog').close());

	$('add-source').addEventListener('change', runAddSearch);
	$('browse-source').addEventListener('change', runBrowse);

	$('sources-recheck').addEventListener('click', renderSources);

	$('cf-save').addEventListener('click', async () => {
		const key = $('cf-key').value.trim();
		if (!key) return toast('Paste a key first', true);

		const saved = await call(api.sources.setCurseForgeKey(key), 'Key rejected');
		// Cleared either way: a rejected key should not sit in the field waiting to be
		// resubmitted, and a saved one has no reason to stay on screen.
		$('cf-key').value = '';
		if (saved) {
			toast('CurseForge key saved');
			renderSources();
		}
	});

	$('cf-clear').addEventListener('click', async () => {
		if (await call(api.sources.setCurseForgeKey(''), 'Could not clear')) {
			toast('CurseForge key removed');
			renderSources();
		}
	});

	$('job-cancel').addEventListener('click', () => {
		if (state.job) api.jobs.cancel(state.job.id);
	});

	api.onJobEvent(onJobEvent);
	api.onReady(start);
}

/**
 * First load.
 *
 * Guarded because it is reached two ways -- the ready event, and the health check below --
 * and whichever arrives second would otherwise reload the instance list and send the view
 * back to home. Harmless at startup, and not harmless at all if someone has already
 * clicked into an instance by the time it lands.
 */
let started = false;
async function start() {
	if (started) return;
	started = true;

	await loadInstances();
	show('home');
}

wire();

// The backend may already be up if this window reloaded, in which case the ready event has
// been and gone. A direct call settles which it is.
api.health().then((result) => { if (result.ok) start(); });
