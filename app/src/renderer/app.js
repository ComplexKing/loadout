'use strict';

/*
 * Renderer.
 *
 * One rule runs through all of it: every string that came from a mod registry, a log or a
 * world file is written with textContent, never innerHTML. All of it is typed by strangers
 * or by the game and served verbatim, so treating any of it as markup would be an
 * injection with extra steps -- and this page holds no API token precisely because it
 * renders such things. The content security policy is the backstop; this is the defence.
 */

const api = window.loadout;

const state = {
	view: 'home',
	instances: [],
	current: null,       // full profile of the open instance
	tab: 'mods',
	contentMode: 'installed',
	contentKind: 'mod',  // mod | resourcepack | shader
	settingsSection: 'sources',
	job: null,
	icons: {},           // installed mod artwork, by file name
	counts: {},
	versions: null,      // Minecraft version catalogue, fetched once
	latestRelease: null,
	versionsPending: null,
	systemMemoryMb: 0,
	searchSeq: 0,
	openLog: null,
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

function bytes(n) {
	if (!n) return '0 B';
	const units = ['B', 'KB', 'MB', 'GB', 'TB'];
	const at = Math.min(Math.floor(Math.log(n) / Math.log(1024)), units.length - 1);
	return (n / Math.pow(1024, at)).toFixed(at === 0 ? 0 : 1) + ' ' + units[at];
}

function when(millis) {
	if (!millis) return 'never';
	const days = Math.floor((Date.now() - millis) / 86400000);
	if (days === 0) return 'today';
	if (days === 1) return 'yesterday';
	if (days < 30) return `${days} days ago`;
	return new Date(millis).toLocaleDateString();
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

const debouncers = new Map();
function debounce(key, fn, wait) {
	clearTimeout(debouncers.get(key));
	debouncers.set(key, setTimeout(fn, wait === undefined ? 260 : wait));
}

/**
 * A stable colour per name.
 *
 * Instances rarely have artwork, and a grid of identical grey squares is unreadable at a
 * glance. Deriving the hue from the name means the same instance is the same colour every
 * launch, so people navigate by colour without being told to.
 */
function avatarFor(name) {
	let hash = 0;
	for (let i = 0; i < name.length; i++) {
		hash = (hash * 31 + name.charCodeAt(i)) >>> 0;
	}
	const hue = hash % 360;
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

/** Artwork, falling back to a lettered tile if it is missing or fails to load. */
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

/** A blank state with an icon, used wherever a list can legitimately be empty. */
function blank(iconName, text, small) {
	const box = el('div', small ? 'blank small' : 'blank');
	const art = el('div', 'blank-art');
	art.appendChild(icon(iconName, small ? 'ico' : 'ico xl'));
	box.appendChild(art);
	box.appendChild(el('p', null, text));
	return box;
}

/* -- custom select --------------------------------------------------------------------- */

/**
 * A dropdown drawn by this page rather than by the platform.
 *
 * A native select on Windows opens its own popup in the system's colours, so a dark
 * interface gets one white list with a blue highlight in the middle of it -- the single
 * control that refuses to match anything around it. Everything else here is styled, so
 * this has to be too.
 *
 * @param options [{value, label, hint}]
 * @param onChange called with the new value
 * @returns an element with .value, .setValue() and .setOptions()
 */
function makeSelect(options, value, onChange) {
	const root = el('div', 'sel');

	const button = el('button', 'sel-button');
	button.type = 'button';
	const label = el('span', 'sel-value');
	button.appendChild(label);
	button.appendChild(icon('i-chevron', 'ico sm'));
	root.appendChild(button);

	const list = el('div', 'sel-list');
	list.hidden = true;
	root.appendChild(list);

	let current = value;
	let items = options;

	const paint = () => {
		const chosen = items.find((o) => o.value === current);
		label.textContent = chosen ? chosen.label : '';

		clear(list);
		for (const option of items) {
			const row = el('div', 'sel-option');
			if (option.value === current) row.classList.add('chosen');

			const tick = icon('i-chevron', 'ico sm sel-tick');
			// A tick would be another glyph for one use; a rotated chevron reads well enough
			// and keeps the sprite sheet to what is actually needed.
			tick.style.transform = 'rotate(-90deg)';
			row.appendChild(tick);
			row.appendChild(el('span', null, option.label));
			if (option.hint) row.appendChild(el('span', 'when', option.hint));

			row.addEventListener('mousedown', (event) => {
				event.preventDefault();
				close();
				if (option.value !== current) {
					current = option.value;
					paint();
					onChange(current);
				}
			});
			list.appendChild(row);
		}
	};

	const close = () => {
		list.hidden = true;
		root.classList.remove('open');
		document.removeEventListener('mousedown', onOutside);
	};

	const onOutside = (event) => {
		if (!root.contains(event.target)) close();
	};

	button.addEventListener('click', () => {
		if (list.hidden) {
			// Only one open at a time, or clicking between two leaves both showing.
			for (const other of $$('.sel.open')) other.querySelector('.sel-button').click();
			list.hidden = false;
			root.classList.add('open');
			document.addEventListener('mousedown', onOutside);
		} else {
			close();
		}
	});

	root.getValue = () => current;
	root.setValue = (next) => { current = next; paint(); };
	root.setOptions = (next) => {
		items = next;
		if (!items.some((o) => o.value === current)) current = items[0] ? items[0].value : null;
		paint();
	};

	paint();
	return root;
}

/** Replaces a placeholder div with a select, keeping the same id for later lookup. */
function mountSelect(placeholderId, options, value, onChange) {
	const holder = $(placeholderId);
	const select = makeSelect(options, value, onChange);
	select.id = placeholderId;
	holder.replaceWith(select);
	return select;
}

/* -- routing -------------------------------------------------------------------------- */

function show(view) {
	state.view = view;

	for (const section of $$('.view')) section.hidden = section.dataset.view !== view;
	for (const button of $$('.rail-btn[data-view]')) {
		button.classList.toggle('active', button.dataset.view === view);
	}
	for (const tile of $$('.rail-tile')) {
		tile.classList.toggle('active',
			view === 'instance' && state.current && tile.dataset.name === state.current.name);
	}

	if (view === 'settings') showSection(state.settingsSection);
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
		? '1 instance' : `${state.instances.length} instances`;
	$('home-empty').hidden = state.instances.length > 0;

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

		const meta = el('div', 'meta-line');
		meta.appendChild(el('span', 'pill', `${instance.modCount} mods`));
		if (instance.enabledCount !== instance.modCount) {
			meta.appendChild(el('span', 'pill off', `${instance.modCount - instance.enabledCount} off`));
		}
		body.appendChild(meta);

		card.appendChild(body);
		card.addEventListener('click', () => openInstance(instance.name));
		grid.appendChild(card);
	}
}

async function openInstance(name) {
	const profile = await call(api.profiles.get(name), 'Could not open instance');
	if (!profile) return;

	state.current = profile;
	state.icons = {};
	state.counts = {};
	$('content-search').value = '';
	$('dup-name').value = name + ' copy';
	$('exp-path').value = '';

	const heroIcon = $('hero-icon');
	const { background, initial } = avatarFor(profile.name);
	heroIcon.style.background = background;
	heroIcon.textContent = initial;

	$('hero-name').textContent = profile.name;
	paintHeroMeta(profile);

	show('instance');
	switchTab(state.tab);

	loadModIcons(profile.name);
	refreshCounts(profile.name);
}

function paintHeroMeta(profile) {
	const meta = $('hero-meta');
	clear(meta);
	meta.appendChild(el('span', 'pill', `Minecraft ${profile.minecraftVersion}`));
	meta.appendChild(el('span', 'pill', profile.loader));
	const mods = profile.mods.filter((m) => (m.contentType || 'mod') === 'mod').length;
	meta.appendChild(el('span', 'pill', mods === 1 ? '1 mod' : `${mods} mods`));
}

/** Fills the counts beside each nav item, so the sidebar says what is in there. */
/** How many tracked entries are of one kind. Older entries have no kind and are mods. */
function countOfKind(kind) {
	if (!state.current) return 0;
	return state.current.mods.filter((m) => (m.contentType || 'mod') === kind).length;
}

async function refreshCounts(name) {
	// Hidden rather than emptied: an empty count still draws its pill background, which
	// reads as a stray dash beside every section that happens to have nothing in it.
	const set = (key, value) => {
		const node = $('count-' + key);
		if (!node) return;
		node.textContent = value > 0 ? String(value) : '';
		node.hidden = !(value > 0);
	};

	set('mods', countOfKind('mod'));
	set('resourcepack', countOfKind('resourcepack'));
	set('shader', countOfKind('shader'));

	const [packs, shaders, worlds, servers, shots] = await Promise.all([
		call(api.instance.packs(name, 'resourcepack')),
		call(api.instance.packs(name, 'shader')),
		call(api.instance.worlds(name)),
		call(api.instance.servers(name)),
		call(api.instance.screenshots(name)),
	]);

	if (!state.current || state.current.name !== name) return;

	// The folder is the truth for packs: one dropped in by hand is there whether or not
	// Loadout installed it, and a count that disagreed with the folder would be worse than
	// no count.
	state.counts = {
		resourcepack: packs ? packs.packs.length : 0,
		shader: shaders ? shaders.packs.length : 0,
		worlds: worlds ? worlds.worlds.length : 0,
		servers: servers ? servers.servers.length : 0,
		screenshots: shots ? shots.screenshots.length : 0,
	};

	set('resourcepack', state.counts.resourcepack);
	set('shader', state.counts.shader);
	set('worlds', state.counts.worlds);
	set('servers', state.counts.servers);
	set('screenshots', state.counts.screenshots);
}

async function refreshCurrent() {
	if (!state.current) return;
	const profile = await call(api.profiles.get(state.current.name));
	if (!profile) return;

	state.current = profile;
	paintHeroMeta(profile);
	if (state.tab === 'mods') renderContent();
	refreshCounts(profile.name);
}

/* -- instance tabs --------------------------------------------------------------------- */

// Worlds join the content panel so they get the same Installed/Add switch. They render
// differently when installed -- a world is a folder the game owns, not a tracked file --
// but the searching half is identical.
const CONTENT_TABS = {
	mods: 'mod', resourcepack: 'resourcepack', shader: 'shader', worlds: 'world',
};

function switchTab(name) {
	state.tab = name;

	for (const item of $$('#subnav .subnav-item')) {
		item.classList.toggle('active', item.dataset.tab === name);
	}

	const isContent = name in CONTENT_TABS;
	if (isContent) state.contentKind = CONTENT_TABS[name];

	for (const panel of $$('.instance-panels .panel')) {
		panel.hidden = panel.dataset.panel !== (isContent ? 'content' : name);
	}

	if (isContent) {
		$('content-search').placeholder = searchPlaceholder();
		renderContent();
	}
	if (name === 'servers') renderServers();
	if (name === 'screenshots') renderScreenshots();
	if (name === 'logs') renderLogs();
	if (name === 'versions') renderSnapshots();
	if (name === 'settings') renderInstanceOptions();
}

/** What the search box is for, which differs per kind and per mode. */
function searchPlaceholder() {
	const plural = {
		mod: 'mods', resourcepack: 'resource packs', shader: 'shaders', world: 'worlds',
	}[state.contentKind] || 'content';
	return state.contentMode === 'installed' ? `Filter ${plural}` : `Search ${plural}`;
}

function setContentMode(mode) {
	state.contentMode = mode;
	for (const button of $$('#content-mode .seg-btn')) {
		button.classList.toggle('active', button.dataset.mode === mode);
	}
	$('content-search').value = '';
	$('content-search').placeholder = searchPlaceholder();

	// Only meaningful when searching a registry.
	$('content-source').hidden = mode === 'installed';
	$('content-sort').hidden = mode === 'installed';

	renderContent();
}

function renderContent() {
	if (state.contentMode === 'installed') {
		renderInstalled();
	} else {
		runContentSearch();
	}
}

/* -- installed content ------------------------------------------------------------------ */

async function renderInstalled() {
	const body = $('content-body');
	clear($('content-notes'));

	if (state.contentKind === 'mod') {
		body.className = 'rows';
		renderInstalledMods(body);
		return;
	}

	body.className = 'rows';
	clear(body);

	if (state.contentKind === 'world') {
		await renderInstalledWorlds(body);
		return;
	}

	const data = await call(api.instance.packs(state.current.name, state.contentKind));
	if (!data) return;

	if (data.packs.length === 0) {
		body.appendChild(blank(state.contentKind === 'shader' ? 'i-sparkle' : 'i-image',
			`No ${state.contentKind === 'shader' ? 'shader packs' : 'resource packs'} yet. `
			+ 'Use Add to find some.', true));
		return;
	}

	const filter = $('content-search').value.trim().toLowerCase();
	for (const pack of data.packs) {
		if (filter && !pack.name.toLowerCase().includes(filter)) continue;

		const row = el('div', 'row');
		row.style.gridTemplateColumns = 'minmax(0,1fr) auto';

		const info = el('div', 'row-body');
		info.appendChild(el('div', 'row-title', pack.name));
		info.appendChild(el('div', 'row-sub', `${bytes(pack.sizeBytes)} · added ${when(pack.modifiedAt)}`));
		row.appendChild(info);

		const actions = el('div', 'row-actions');
		if (!pack.enabled) actions.appendChild(el('span', 'pill off', 'off'));
		row.appendChild(actions);
		body.appendChild(row);
	}
}

async function renderInstalledWorlds(body) {
	const data = await call(api.instance.worlds(state.current.name), 'Could not read worlds');
	if (!data) return;

	const filter = $('content-search').value.trim().toLowerCase();
	const worlds = data.worlds.filter((w) =>
		!filter || w.name.toLowerCase().includes(filter) || w.folder.toLowerCase().includes(filter));

	if (worlds.length === 0) {
		body.appendChild(blank('i-globe', data.worlds.length === 0
			? 'No worlds yet. Play the instance, or switch to Add to download one.'
			: 'Nothing matches that filter.', true));
		return;
	}

	for (const world of worlds) {
		const row = el('div', 'row');
		row.style.gridTemplateColumns = 'minmax(0,1fr) auto';

		const info = el('div', 'row-body');
		info.appendChild(el('div', 'row-title', world.name));
		// The folder is shown when it differs, because that is what is on disk and what a
		// backup or a manual copy would be called.
		info.appendChild(el('div', 'row-sub', world.folder !== world.name
			? `${world.folder} · ${bytes(world.sizeBytes)} · played ${when(world.lastPlayed)}`
			: `${bytes(world.sizeBytes)} · played ${when(world.lastPlayed)}`));
		row.appendChild(info);
		body.appendChild(row);
	}
}

function renderInstalledMods(body) {
	clear(body);
	if (!state.current) return;

	const filter = $('content-search').value.trim().toLowerCase();
	const mods = state.current.mods
		.filter((mod) => (mod.contentType || 'mod') === 'mod')
		.filter((mod) =>
			!filter
			|| (mod.modId || '').toLowerCase().includes(filter)
			|| mod.fileName.toLowerCase().includes(filter));

	if (mods.length === 0) {
		body.appendChild(blank('i-puzzle', countOfKind('mod') === 0
			? 'No mods yet. Switch to Add to install some.'
			: 'Nothing matches that filter.', true));
		return;
	}

	for (const mod of mods) body.appendChild(modRow(mod));
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
			await loadInstances();
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

/**
 * Fills in installed-mod artwork once the registries answer.
 *
 * Patches the images in place rather than re-rendering, so nothing moves under the cursor
 * and a row mid-hover is not replaced beneath it.
 */
async function loadModIcons(name) {
	const data = await call(api.mods.icons(name));
	if (!data || !state.current || state.current.name !== name) return;

	state.icons = data.icons || {};

	for (const node of $$('#content-body .art[data-file]')) {
		const url = state.icons[node.dataset.file];
		if (!url) continue;

		const image = document.createElement('img');
		image.className = 'art';
		image.alt = '';
		image.loading = 'lazy';
		image.src = url;
		image.dataset.file = node.dataset.file;
		image.addEventListener('error', () => image.replaceWith(node));
		node.replaceWith(image);
	}
}

/* -- searching a registry ---------------------------------------------------------------- */

function showSkeletons(node, count) {
	clear(node);
	for (let i = 0; i < (count || 6); i++) {
		const card = el('div', 'skeleton-card');
		card.appendChild(el('div', 'skeleton skeleton-art'));
		const lines = el('div', 'skeleton-lines');
		for (const width of ['58%', '82%', '40%']) {
			const line = el('div', 'skeleton skeleton-line');
			line.style.width = width;
			lines.appendChild(line);
		}
		card.appendChild(lines);
		node.appendChild(card);
	}
}

/**
 * Runs a search and renders it.
 *
 * Every call carries a sequence number so a slower earlier request landing after a newer
 * one is dropped rather than overwriting fresher results.
 */
async function search({ profileName, query, sort, source, type, resultsNode, notesNode }) {
	const seq = ++state.searchSeq;

	if (resultsNode.childElementCount === 0) showSkeletons(resultsNode, 6);

	const data = await call(api.search({ q: query, profile: profileName, sort, source, type, limit: 40 }));
	if (seq !== state.searchSeq) return;

	const retry = () => search({ profileName, query, sort, source, type, resultsNode, notesNode });

	clear(notesNode);
	if (!data) {
		clear(resultsNode);
		notesNode.appendChild(problemNotice('Could not reach the registries.', retry));
		return;
	}
	for (const note of data.notes || []) notesNode.appendChild(problemNotice(note, retry));

	clear(resultsNode);
	if (data.results.length === 0) {
		resultsNode.appendChild(blank('i-search', query
			? `Nothing matched "${query}" for this version.` : 'No results.', true));
		return;
	}

	const installed = new Set((state.current?.mods || [])
		.filter((m) => m.projectId).map((m) => `${m.source}:${m.projectId}`));

	for (const mod of data.results) {
		// Carried on the result so the card can tell a mod from a resource pack; the
		// registries do not repeat the kind per result, only per query.
		mod.kind = type || 'mod';
		resultsNode.appendChild(modCard(mod, installed.has(`${mod.source}:${mod.id}`), profileName));
	}
}

/**
 * A source failure, stated plainly with a way to try again.
 *
 * Registries go down, keys expire and laptops lose wifi, and none of that should read as
 * the application being broken.
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

	// A split control: the button installs the newest build, the chevron opens the rest.
	// Choosing a version every time would be tedious; never being able to is the real gap,
	// since a beta above the last stable release is normal in Minecraft modding.
	const group = el('div', 'split');
	const install = el('button', 'btn primary sm', alreadyInstalled ? 'Installed' : 'Install');
	install.disabled = alreadyInstalled || !profileName;

	install.addEventListener('click', async () => {
		install.disabled = true;
		install.textContent = 'Installing…';
		const started = await call(
			api.mods.install(profileName, mod.source, mod.id, mod.kind || 'mod'), 'Install failed');
		if (!started) {
			install.disabled = false;
			install.textContent = 'Install';
		}
	});
	group.appendChild(install);

	if (profileName && mod.kind !== 'world') {
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

function runContentSearch() {
	if (!state.current) return;
	const body = $('content-body');
	body.className = 'cards';

	search({
		profileName: state.current.name,
		query: $('content-search').value.trim(),
		sort: $('content-sort').getValue ? $('content-sort').getValue() : 'RELEVANCE',
		source: $('content-source').getValue ? $('content-source').getValue() : 'all',
		type: state.contentKind,
		resultsNode: body,
		notesNode: $('content-notes'),
	});
}

function runBrowse() {
	search({
		profileName: $('browse-target').getValue ? $('browse-target').getValue() : undefined,
		query: $('browse-search').value.trim(),
		sort: $('browse-sort').getValue ? $('browse-sort').getValue() : 'RELEVANCE',
		source: $('browse-source').getValue ? $('browse-source').getValue() : 'all',
		type: $('browse-type').getValue ? $('browse-type').getValue() : 'mod',
		resultsNode: $('browse-results'),
		notesNode: $('browse-notes'),
	});
}

function syncBrowseTargets() {
	const select = $('browse-target');
	if (!select.setOptions) return;

	const options = state.instances.map((i) => ({
		value: i.name, label: i.name, hint: i.minecraftVersion,
	}));
	select.setOptions(options.length ? options
		: [{ value: '', label: 'No instances' }]);

	if (state.current && options.some((o) => o.value === state.current.name)) {
		select.setValue(state.current.name);
	}
}

/* -- version picker ---------------------------------------------------------------------- */

async function openVersions(mod, profileName) {
	const dialog = $('versions-dialog');
	const list = $('vd-list');

	$('vd-title').textContent = mod.title;
	$('vd-sub').textContent = 'Loading builds…';
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
		: `${data.versions.length} builds for ${instance ? instance.minecraftVersion : 'this instance'}`;

	data.versions.forEach((version, index) => {
		const row = el('div', 'row');
		row.style.gridTemplateColumns = 'minmax(0,1fr) auto';

		const body = el('div', 'row-body');
		body.appendChild(el('div', 'row-title', version.versionNumber || version.fileName));
		body.appendChild(el('div', 'row-sub', `${version.fileName} · ${bytes(version.fileSize)}`));
		row.appendChild(body);

		const actions = el('div', 'row-actions');
		// Saying which one a plain Install would have picked saves comparing version strings.
		if (index === 0) actions.appendChild(el('span', 'pill ok', 'latest'));

		if (!version.downloadable) {
			actions.appendChild(el('span', 'pill off', 'not downloadable'));
		} else {
			const pick = el('button', 'btn sm', 'Install');
			pick.addEventListener('click', async () => {
				pick.disabled = true;
				const started = await call(
					api.mods.installVersion(profileName, mod.source, mod.id, version.versionId,
						mod.kind || 'mod'),
					'Install failed');
				if (started) dialog.close(); else pick.disabled = false;
			});
			actions.appendChild(pick);
		}

		row.appendChild(actions);
		list.appendChild(row);
	});
}

/* -- worlds, servers, logs ---------------------------------------------------------------- */

async function renderServers() {
	const body = $('servers-body');
	clear(body);

	const data = await call(api.instance.servers(state.current.name), 'Could not read servers');
	if (!data) return;

	if (data.servers.length === 0) {
		body.appendChild(blank('i-server',
			'No servers saved. The multiplayer list appears here once you add one.', true));
		return;
	}

	for (const server of data.servers) {
		const row = el('div', 'row');
		row.style.gridTemplateColumns = 'minmax(0,1fr) auto';

		const info = el('div', 'row-body');
		info.appendChild(el('div', 'row-title', server.name));
		info.appendChild(el('div', 'row-sub', server.address));
		row.appendChild(info);
		body.appendChild(row);
	}
}

/**
 * The screenshots the game has taken.
 *
 * Shown from disk rather than copied or re-encoded: these are the user's own files inside
 * their own instance, and the paths come from the API rather than from anything the page
 * chose, which is what makes allowing local images here narrow enough to be reasonable.
 */
async function renderScreenshots() {
	const body = $('shots-body');
	clear(body);

	const data = await call(api.instance.screenshots(state.current.name), 'Could not read screenshots');
	if (!data) return;

	if (data.screenshots.length === 0) {
		body.className = '';
		body.appendChild(blank('i-image',
			'No screenshots yet. Press F2 in game and they appear here.', true));
		return;
	}

	body.className = 'shots';
	for (const shot of data.screenshots) {
		const card = el('div', 'shot');
		card.title = shot.name;

		const image = document.createElement('img');
		image.loading = 'lazy';
		image.alt = '';
		// A Windows path is not a URL: the separators have to be flipped and each segment
		// escaped, or a space or '#' in a file name breaks the reference. Built from a char
		// code so no literal backslash appears in this file.
		const sep = String.fromCharCode(92);
		// The drive segment is left alone: encoding its colon gives C%3A, which is not a
		// drive any more.
		const parts = shot.path.split(sep).join('/').split('/')
			.map((part, at) => (at === 0 ? part : encodeURIComponent(part)));
		image.src = 'file:///' + parts.join('/');
		card.appendChild(image);

		const meta = el('div', 'shot-meta');
		meta.appendChild(el('span', 'name', shot.name));
		meta.appendChild(el('span', null, when(shot.takenAt)));
		card.appendChild(meta);

		// Opens in whatever views images on this machine, which is better than a viewer
		// built here that would do less.
		card.addEventListener('click', () => api.openPath(shot.path));
		body.appendChild(card);
	}
}

async function renderLogs() {
	const list = $('logs-list');
	clear(list);

	const data = await call(api.instance.logs(state.current.name), 'Could not read logs');
	if (!data) return;

	if (data.logs.length === 0) {
		list.appendChild(blank('i-doc', 'No logs yet.', true));
		return;
	}

	for (const log of data.logs) {
		const row = el('div', 'row');
		row.style.gridTemplateColumns = 'minmax(0,1fr)';
		if (state.openLog === log.name) row.classList.add('chosen');

		const info = el('div', 'row-body');
		info.appendChild(el('div', 'row-title', log.name));
		info.appendChild(el('div', 'row-sub', `${bytes(log.sizeBytes)} · ${when(log.modifiedAt)}`));
		row.appendChild(info);

		row.addEventListener('click', () => openLog(log.name));
		list.appendChild(row);
	}
}

async function openLog(name) {
	state.openLog = name;
	const view = $('log-view');
	view.textContent = 'Loading…';

	for (const row of $$('#logs-list .row')) {
		row.classList.toggle('chosen', row.querySelector('.row-title').textContent === name);
	}

	const data = await call(api.instance.logTail(state.current.name, name), 'Could not read log');
	view.textContent = data ? (data.text || '(empty)') : 'Could not read that log.';
	// The end is the interesting part of a log, so start there.
	view.scrollTop = view.scrollHeight;
}

/* -- versions and history ------------------------------------------------------------------ */

async function renderSnapshots() {
	const list = $('snapshots');
	clear(list);

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
			if (await call(api.snapshots.rollback(state.current.name, snapshot.id), 'Rollback failed')) {
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

/* -- settings -------------------------------------------------------------------------------- */

function showSection(name) {
	state.settingsSection = name;
	for (const item of $$('#settings-nav .subnav-item')) {
		item.classList.toggle('active', item.dataset.section === name);
	}
	for (const panel of $$('.settings-panels .panel')) {
		panel.hidden = panel.dataset.section !== name;
	}

	if (name === 'sources') renderSources();
	if (name === 'java') { renderJavas(); renderGlobalOptions(); }
	if (name === 'accounts') renderAccounts();
	if (name === 'about') renderAbout();
}

async function renderSources() {
	const box = $('sources');
	clear(box);
	box.appendChild(el('p', 'sub', 'Checking…'));

	// Verified, not merely configured: this screen exists to explain why a source is not
	// working, and "a key is present" does not answer that.
	const data = await call(api.sources.list(true));
	clear(box);
	if (!data) return;

	for (const source of data.sources) {
		const row = el('div', 'row');
		row.style.gridTemplateColumns = 'minmax(0,1fr) auto';

		const body = el('div', 'row-body');
		body.appendChild(el('div', 'row-title', source.name));
		body.appendChild(el('div', 'row-sub',
			source.reason || (source.available ? 'Working' : 'Not set up')));
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
		row.style.gridTemplateColumns = 'minmax(0,1fr)';
		const body = el('div', 'row-body');
		body.appendChild(el('div', 'row-title', `Java ${install.majorVersion}`));
		body.appendChild(el('div', 'row-sub', install.path));
		row.appendChild(body);
		box.appendChild(row);
	}
}

/**
 * The accounts screen.
 *
 * Sign-in is not built yet -- it waits on an approved Azure application -- and this says
 * so plainly rather than showing a button that cannot work. Stating why a thing is absent
 * is more useful than hiding that it should be there.
 */
function renderAccounts() {
	const box = $('accounts-body');
	clear(box);

	const block = el('div', 'card-block');
	block.appendChild(el('h3', null, 'Microsoft sign-in'));
	block.appendChild(el('p', 'sub',
		'Loadout needs an approved Azure application before it can sign anyone in. That '
		+ 'review is pending, so this is the one part of the launcher that is not wired up '
		+ 'yet.'));

	const note = el('p', 'sub spaced');
	note.textContent = 'Offline play still requires an account that has signed in at least '
		+ 'once on this machine. Skipping that check would make this a way of playing '
		+ 'without a licence, which is a different product to the one this is.';
	block.appendChild(note);

	const button = el('button', 'btn');
	button.textContent = 'Sign in with Microsoft';
	button.disabled = true;
	button.title = 'Waiting on Azure application approval';
	block.appendChild(el('div', 'spaced')).appendChild(button);

	box.appendChild(block);
}

function renderAbout() {
	const box = $('about-body');
	clear(box);

	const line = (label, value) => {
		const row = el('div', 'row');
		row.style.gridTemplateColumns = 'minmax(0,1fr) auto';
		const body = el('div', 'row-body');
		body.appendChild(el('div', 'row-title', label));
		row.appendChild(body);
		row.appendChild(el('span', 'row-sub', value));
		box.appendChild(row);
	};

	line('Version', '0.1.0');
	call(api.health()).then((health) => {
		if (health) line('Data folder', health.home);
	});
}

/* -- appearance ------------------------------------------------------------------------------- */

const ACCENTS = [
	{ value: '#35c37d', label: 'Green' },
	{ value: '#4c9df0', label: 'Blue' },
	{ value: '#a97bf0', label: 'Violet' },
	{ value: '#e8894a', label: 'Amber' },
	{ value: '#e2596e', label: 'Rose' },
	{ value: '#3ec8c0', label: 'Teal' },
];

/**
 * Reads a stored preference, tolerating storage being unavailable.
 *
 * Private windows and locked-down profiles make localStorage throw rather than return
 * nothing, so every access has to be guarded or the interface fails to start over a
 * setting nobody set.
 */
function stored(key, fallback) {
	try {
		return localStorage.getItem(key) ?? fallback;
	} catch {
		return fallback;
	}
}

function remember(key, value) {
	try {
		localStorage.setItem(key, value);
	} catch {
		// A preference that cannot be saved is a preference that lasts this session.
	}
}

function applyAccent(hex) {
	const root = document.documentElement;
	root.style.setProperty('--accent', hex);
	root.style.setProperty('--accent-2', lighten(hex, 0.14));
	root.style.setProperty('--accent-dim', lighten(hex, -0.2));
	root.style.setProperty('--accent-glow', hexToRgba(hex, 0.16));
	// The ink on a primary button has to stay readable whatever the accent becomes.
	root.style.setProperty('--accent-ink', lighten(hex, -0.62));
}

function lighten(hex, amount) {
	const n = parseInt(hex.slice(1), 16);
	const channel = (shift) => {
		const v = (n >> shift) & 255;
		const next = amount >= 0 ? v + (255 - v) * amount : v * (1 + amount);
		return Math.max(0, Math.min(255, Math.round(next)));
	};
	return `rgb(${channel(16)}, ${channel(8)}, ${channel(0)})`;
}

function hexToRgba(hex, alpha) {
	const n = parseInt(hex.slice(1), 16);
	return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

function applyDensity(mode) {
	document.documentElement.dataset.density = mode;
}

function renderAppearance() {
	const box = $('accents');
	clear(box);

	const chosen = stored('accent', ACCENTS[0].value);
	for (const accent of ACCENTS) {
		const swatch = el('button', 'swatch');
		swatch.style.background = accent.value;
		swatch.title = accent.label;
		if (accent.value === chosen) swatch.classList.add('chosen');

		swatch.addEventListener('click', () => {
			remember('accent', accent.value);
			applyAccent(accent.value);
			renderAppearance();
		});
		box.appendChild(swatch);
	}
}

/* -- launch options ------------------------------------------------------------------------------ */

/**
 * The memory, Java, window and hook settings, drawn once and used in two places.
 *
 * Global defaults and a per-instance override of the same shape, so the same renderer
 * serves both -- with the override switches present only on the instance copy. Writing it
 * twice is how the two would come to disagree about what a field means.
 *
 * @param mount where to draw
 * @param options the values being edited
 * @param defaults what an un-overridden group would use, or null when editing the defaults
 * @param onSave called with the collected values
 */
function renderOptions(mount, options, defaults, onSave) {
	clear(mount);
	const perInstance = defaults !== null;
	const fields = {};

	const group = (key, title, build) => {
		const box = el('div', 'opt-group');

		const head = el('div', 'opt-head');
		head.appendChild(el('span', 'opt-title', title));

		const body = el('div', 'opt-fields');

		if (perInstance) {
			const row = el('div', 'switch-row');
			const label = el('span', null, 'Override');
			const toggle = el('button', 'switch');
			toggle.type = 'button';

			let on = Boolean(options['override' + key]);
			const paint = () => {
				toggle.classList.toggle('on', on);
				body.classList.toggle('inherited', !on);
				toggle.setAttribute('aria-pressed', String(on));
			};

			toggle.addEventListener('click', () => { on = !on; paint(); });
			fields['override' + key] = () => on;

			row.appendChild(label);
			row.appendChild(toggle);
			head.appendChild(row);
			setTimeout(paint, 0);
		}

		box.appendChild(head);
		build(body);
		box.appendChild(body);
		mount.appendChild(box);
	};

	const numberField = (parent, key, label, value, placeholder) => {
		const wrap = el('label', 'opt-field');
		wrap.appendChild(el('span', null, label));

		const input = document.createElement('input');
		input.type = 'number';
		input.min = '0';
		input.value = value === undefined || value === null ? '' : String(value);
		input.placeholder = placeholder === undefined ? '' : String(placeholder);
		wrap.appendChild(input);
		parent.appendChild(wrap);

		fields[key] = () => (input.value.trim() === '' ? null : Number(input.value));
		return input;
	};

	const textField = (parent, key, label, value, placeholder, wide) => {
		const wrap = el('label', 'opt-field' + (wide ? ' wide' : ''));
		wrap.appendChild(el('span', null, label));

		const input = document.createElement('input');
		input.type = 'text';
		input.value = value || '';
		input.placeholder = placeholder || '';
		wrap.appendChild(input);
		parent.appendChild(wrap);

		fields[key] = () => (input.value.trim() === '' ? null : input.value.trim());
	};

	const fallback = (key) => (defaults ? defaults[key] : undefined);

	group('Memory', 'Memory', (body) => {
		numberField(body, 'memoryMinMb', 'Minimum (MB)', options.memoryMinMb, fallback('memoryMinMb') ?? '');
		const max = numberField(body, 'memoryMaxMb', 'Maximum (MB)', options.memoryMaxMb,
			fallback('memoryMaxMb') ?? '');

		const hint = el('div', 'memory-hint');
		body.appendChild(hint);

		const describe = () => {
			const value = Number(max.value);
			if (!value) {
				hint.textContent = state.systemMemoryMb
					? `This machine has about ${Math.round(state.systemMemoryMb / 1024)} GB.`
					: '';
				return;
			}
			// Leaving the whole machine to the JVM starves the operating system, and the
			// symptom is the desktop stuttering rather than anything blamed on the launcher.
			const share = state.systemMemoryMb ? value / state.systemMemoryMb : 0;
			hint.textContent = share > 0.75
				? `${(value / 1024).toFixed(1)} GB of about `
					+ `${Math.round(state.systemMemoryMb / 1024)} GB — leave some for the system.`
				: `${(value / 1024).toFixed(1)} GB`
					+ (state.systemMemoryMb
						? ` of about ${Math.round(state.systemMemoryMb / 1024)} GB` : '');
		};

		max.addEventListener('input', describe);
		describe();
	});

	group('Java', 'Java', (body) => {
		textField(body, 'javaPath', 'Java executable', options.javaPath,
			fallback('javaPath') || 'Chosen automatically', true);
		textField(body, 'jvmArgs', 'Extra JVM arguments', options.jvmArgs,
			fallback('jvmArgs') || '', true);
	});

	group('Window', 'Game window', (body) => {
		numberField(body, 'windowWidth', 'Width', options.windowWidth, fallback('windowWidth') ?? 854);
		numberField(body, 'windowHeight', 'Height', options.windowHeight, fallback('windowHeight') ?? 480);
	});

	group('Commands', 'Hooks', (body) => {
		textField(body, 'preLaunchCommand', 'Before launch', options.preLaunchCommand,
			fallback('preLaunchCommand') || '', true);
		textField(body, 'postExitCommand', 'After exit', options.postExitCommand,
			fallback('postExitCommand') || '', true);
	});

	const save = el('button', 'btn primary spaced', 'Save');
	save.addEventListener('click', async () => {
		const collected = {};
		for (const [key, read] of Object.entries(fields)) collected[key] = read();

		save.disabled = true;
		const done = await onSave(collected);
		save.disabled = false;
		if (done) toast('Saved');
	});
	mount.appendChild(save);
}

async function renderInstanceOptions() {
	const data = await call(api.options.get(state.current.name), 'Could not load settings');
	if (!data) return;

	renderOptions($('instance-options'), data.options, data.defaults, async (values) =>
		Boolean(await call(api.options.set(state.current.name, values), 'Could not save')));
}

async function renderGlobalOptions() {
	const data = await call(api.options.defaults(), 'Could not load defaults');
	if (!data) return;

	state.systemMemoryMb = data.systemMemoryMb || 0;
	renderOptions($('global-options'), data.defaults, null, async (values) =>
		Boolean(await call(api.options.setDefaults(values), 'Could not save')));
}

/* -- jobs -------------------------------------------------------------------------------------- */

function onJobEvent(event) {
	const job = event.job;

	if (job.state === 'running') {
		state.job = job;
		$('jobbar').hidden = false;
		$('job-title').textContent = `${job.kind}: ${job.subject}`;
		$('job-stage').textContent = job.stage || '';

		const fill = $('job-fill');
		if (job.total > 0) {
			const percent = Math.round((job.done / job.total) * 100);
			fill.classList.remove('pending');
			fill.style.width = `${percent}%`;
			$('job-percent').textContent = `${percent}%`;
		} else {
			fill.classList.add('pending');
			$('job-percent').textContent = '';
		}
		return;
	}

	if (state.job && state.job.id === job.id) {
		state.job = null;
		$('jobbar').hidden = true;
	}

	if (job.state === 'failed') toast(`${job.kind} failed: ${job.error}`, true);
	else if (job.state === 'cancelled') toast(`${job.kind} cancelled`);
	else reportSuccess(job);

	if (state.current) refreshCurrent();
	loadInstances();
}

function reportSuccess(job) {
	const result = job.result || {};

	if (job.kind === 'install') {
		const added = (result.installed || []).length + (result.upgraded || []).length;
		if ((result.blocked || []).length > 0) {
			toast(`${result.blocked.join(', ')} must be downloaded from its own page`, true);
		} else if (added > 0) {
			toast(`Installed ${added} file${added === 1 ? '' : 's'}`);
			if (state.view === 'instance' && state.contentMode === 'add') runContentSearch();
			else if (state.view === 'browse') runBrowse();
		} else if ((result.alreadyPresent || []).length > 0) {
			toast('Already installed');
		} else if ((result.unavailable || []).length > 0) {
			toast(`No build for this version: ${result.unavailable.join(', ')}`, true);
		}
		return;
	}

	if (job.kind === 'migrate') {
		if (result.applied) {
			toast(`Moved version — ${result.modsChanged} mods changed`);
			renderSnapshots();
		} else if (result.plan) {
			renderPlan(result.plan);
		}
		return;
	}

	if (job.kind === 'export') {
		toast(`Exported ${result.files} files to ${result.path}`);
		return;
	}

	if (job.kind === 'launch') toast(`Minecraft exited with code ${result.exitCode}`);
}

/* -- version combobox ---------------------------------------------------------------------------- */

/**
 * The Minecraft version catalogue, fetched once and shared by every picker.
 *
 * Nine hundred versions is too many for a select and a few hundred kilobytes is too much
 * to fetch per keystroke, so it is loaded on first use and kept.
 */
async function loadVersions() {
	if (state.versions) return state.versions;
	if (state.versionsPending) return state.versionsPending;

	// Two pickers opening at once should make one request, not two.
	state.versionsPending = call(api.minecraftVersions(), 'Could not load Minecraft versions')
		.then((data) => {
			state.versionsPending = null;
			if (!data) return null;
			state.versions = data.versions;
			state.latestRelease = data.latestRelease;
			return state.versions;
		});

	return state.versionsPending;
}

/**
 * Turns a markup block into a filtering version picker.
 *
 * Built as a factory rather than wired once, because the same control is wanted in two
 * places -- creating an instance and moving an existing one -- and a second copy with
 * hardcoded element ids is how the two drift apart.
 *
 * @param prefix the id prefix its elements share
 */
function makeVersionCombo(prefix) {
	const input = $(`${prefix}-version`) || $(`${prefix}-target`);
	const list = $(`${prefix}-version-list`);
	const options = $(`${prefix}-version-options`);
	const snapshots = $(`${prefix}-snapshots`);
	const snapshotsRow = $(`${prefix}-snapshots-row`);
	const caret = $(`${prefix}-caret`);

	let marked = -1;

	const matches = () => {
		if (!state.versions) return [];

		const typed = input.value.trim().toLowerCase();
		const eligible = state.versions.filter((v) => snapshots.checked || v.type === 'release');

		// Text that exactly names a version is a selection, not a search. Filtering on it
		// would open the list showing only the row already chosen, which is useless -- the
		// reason to open it is to see the alternatives.
		const isSelection = eligible.some((v) => v.id.toLowerCase() === typed);
		return (isSelection || !typed
			? eligible
			: eligible.filter((v) => v.id.toLowerCase().includes(typed))).slice(0, 120);
	};

	const paint = () => {
		const found = matches();
		clear(options);
		marked = -1;

		if (!state.versions) {
			options.appendChild(el('div', 'combo-empty', 'Loading versions…'));
			return;
		}
		if (found.length === 0) {
			options.appendChild(el('div', 'combo-empty', 'No version matches that.'));
			return;
		}

		for (const version of found) {
			const option = el('div', 'combo-option');
			option.setAttribute('role', 'option');
			option.appendChild(el('span', 'id', version.id));

			if (version.id === state.latestRelease) option.appendChild(el('span', 'pill ok', 'latest'));
			else if (version.type !== 'release') {
				option.appendChild(el('span', 'pill', version.type.replace('old_', '')));
			}
			if (version.releasedAt) {
				option.appendChild(el('span', 'when', new Date(version.releasedAt).getFullYear()));
			}

			// mousedown, not click: the input blurs first on click and the blur handler
			// closes the list before the click ever lands.
			option.addEventListener('mousedown', (event) => {
				event.preventDefault();
				choose(version.id);
			});
			options.appendChild(option);
		}
	};

	const open = () => {
		paint();
		list.hidden = false;
		input.setAttribute('aria-expanded', 'true');

		// Opened on a chosen version: put it under the cursor rather than making anyone
		// scroll to find where they already are.
		const chosen = input.value.trim().toLowerCase();
		const rows = Array.from(options.querySelectorAll('.combo-option'));
		const at = rows.findIndex((o) => o.querySelector('.id').textContent.toLowerCase() === chosen);

		if (at >= 0) {
			marked = at;
			rows[at].classList.add('marked');
			// scrollTop rather than scrollIntoView, which scrolls every ancestor that can
			// scroll and slides the dialog's own heading off the top.
			list.scrollTop = rows[at].offsetTop - (list.clientHeight / 2) + (rows[at].offsetHeight / 2);
		}
	};

	const close = () => {
		list.hidden = true;
		input.setAttribute('aria-expanded', 'false');
		marked = -1;
	};

	const choose = (id) => {
		input.value = id;
		close();
		if (combo.onChoose) combo.onChoose(id);
	};

	const move = (delta) => {
		const rows = Array.from(options.querySelectorAll('.combo-option'));
		if (rows.length === 0) return;

		const next = marked + delta;
		marked = next < 0 ? rows.length - 1 : (next >= rows.length ? 0 : next);
		rows.forEach((o, i) => o.classList.toggle('marked', i === marked));

		const row = rows[marked];
		if (row.offsetTop < list.scrollTop) {
			list.scrollTop = row.offsetTop;
		} else if (row.offsetTop + row.offsetHeight > list.scrollTop + list.clientHeight) {
			list.scrollTop = row.offsetTop + row.offsetHeight - list.clientHeight;
		}
	};

	input.addEventListener('focus', () => { loadVersions().then(paint); open(); });
	input.addEventListener('input', open);

	snapshotsRow.addEventListener('mousedown', (event) => {
		// The header lives inside the list, so a click on it would blur the input and the
		// blur handler would close the list out from under the toggle.
		event.preventDefault();
		snapshots.checked = !snapshots.checked;
		paint();
	});

	caret.addEventListener('mousedown', (event) => {
		event.preventDefault();
		if (list.hidden) { input.focus(); loadVersions().then(paint); open(); } else close();
	});

	input.addEventListener('keydown', (event) => {
		const isOpen = !list.hidden;

		if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
			event.preventDefault();
			if (!isOpen) open();
			move(event.key === 'ArrowDown' ? 1 : -1);
			return;
		}
		if (event.key === 'Enter' && isOpen && marked >= 0) {
			event.preventDefault();
			const row = options.querySelectorAll('.combo-option')[marked];
			if (row) choose(row.querySelector('.id').textContent);
			return;
		}
		if (event.key === 'Escape' && isOpen) {
			// Closes the list, not the dialog, which is what Escape would otherwise do.
			event.preventDefault();
			event.stopPropagation();
			close();
		}
	});

	input.addEventListener('blur', () => setTimeout(close, 120));

	const combo = {
		get value() { return input.value.trim(); },
		set value(v) { input.value = v; },
		reset(v) { input.value = v || ''; snapshots.checked = false; close(); },
		close,
		onChoose: null,
	};
	return combo;
}

/* -- wiring -------------------------------------------------------------------------------------- */

const SORTS = [
	{ value: 'RELEVANCE', label: 'Relevance' },
	{ value: 'DOWNLOADS', label: 'Downloads' },
	{ value: 'UPDATED', label: 'Recently updated' },
	{ value: 'NEWEST', label: 'Newest' },
];

const SOURCES = [
	{ value: 'all', label: 'All sources' },
	{ value: 'modrinth', label: 'Modrinth' },
	{ value: 'curseforge', label: 'CurseForge' },
];

const KINDS = [
	{ value: 'mod', label: 'Mods' },
	{ value: 'resourcepack', label: 'Resource packs' },
	{ value: 'shader', label: 'Shaders' },
	{ value: 'datapack', label: 'Data packs' },
];

const LOADERS = [
	{ value: 'fabric', label: 'Fabric' },
	{ value: 'quilt', label: 'Quilt' },
	{ value: 'neoforge', label: 'NeoForge' },
	{ value: 'forge', label: 'Forge' },
];

function mountSelects() {
	mountSelect('content-source', SOURCES, 'all', runContentSearch);
	mountSelect('content-sort', SORTS, 'RELEVANCE', runContentSearch);
	$('content-source').hidden = true;
	$('content-sort').hidden = true;

	mountSelect('browse-type', KINDS, 'mod', runBrowse);
	mountSelect('browse-source', SOURCES, 'all', runBrowse);
	mountSelect('browse-sort', SORTS, 'RELEVANCE', runBrowse);
	mountSelect('browse-target', [{ value: '', label: 'No instances' }], '', runBrowse);

	mountSelect('nd-loader-select', LOADERS, 'fabric', () => {});

	mountSelect('density-choice', [
		{ value: 'comfortable', label: 'Comfortable' },
		{ value: 'compact', label: 'Compact' },
	], stored('density', 'comfortable'), (value) => {
		remember('density', value);
		applyDensity(value);
	});

	mountSelect('language-choice', [
		{ value: 'en', label: 'English' },
	], 'en', () => {});
}

let versionCombo = null;
let migrateCombo = null;

function wire() {
	mountSelects();

	versionCombo = makeVersionCombo('nd');
	migrateCombo = makeVersionCombo('mig');
	// Choosing a target invalidates any plan already on screen, which was computed for a
	// different version and would otherwise sit there looking current.
	migrateCombo.onChoose = () => {
		clear($('mig-result'));
		$('mig-apply').disabled = true;
	};

	for (const button of $$('.rail-btn[data-view]')) {
		button.addEventListener('click', () => show(button.dataset.view));
	}
	for (const item of $$('#subnav .subnav-item')) {
		item.addEventListener('click', () => switchTab(item.dataset.tab));
	}
	for (const item of $$('#settings-nav .subnav-item')) {
		item.addEventListener('click', () => showSection(item.dataset.section));
	}
	for (const button of $$('#content-mode .seg-btn')) {
		button.addEventListener('click', () => setContentMode(button.dataset.mode));
	}

	const openNew = () => {
		$('nd-name').value = '';
		versionCombo.reset(state.latestRelease || '');
		$('new-dialog').showModal();
		$('nd-name').focus();
		// Fetched on first open rather than at startup: most sessions never create one.
		loadVersions().then(() => {
			$('nd-version').placeholder = 'Search versions';
			if (!versionCombo.value && state.latestRelease) versionCombo.value = state.latestRelease;
		});
	};
	$('rail-new').addEventListener('click', openNew);
	$('home-new').addEventListener('click', openNew);
	$('blank-new').addEventListener('click', openNew);

	$('new-dialog').addEventListener('close', async (event) => {
		if (event.target.returnValue !== 'create') return;

		const created = await call(api.profiles.create({
			name: $('nd-name').value.trim(),
			minecraftVersion: $('nd-version').value.trim(),
			loader: $('nd-loader-select').getValue(),
		}), 'Could not create instance');

		if (created) {
			toast(`Created ${created.name}`);
			await loadInstances();
			await openInstance(created.name);
		}
	});

	$('content-search').addEventListener('input', () => debounce('content', renderContent,
		state.contentMode === 'installed' ? 120 : 260));

	$('browse-search').addEventListener('input', () => debounce('browse', runBrowse));

	$('play').addEventListener('click', async () => {
		if (await call(api.launch(state.current.name, null), 'Could not launch')) {
			toast('Starting Minecraft…');
		}
	});

	$('open-folder').addEventListener('click', () => {
		api.openPath(state.current.directory);
	});

	$('mig-check').addEventListener('click', async () => {
		const target = migrateCombo.value;
		if (!target) return toast('Enter a Minecraft version first', true);
		$('mig-apply').disabled = true;
		await call(api.migrate(state.current.name, target, false, false), 'Could not check');
	});

	$('mig-apply').addEventListener('click', async () => {
		await call(api.migrate(state.current.name, migrateCombo.value, true, false),
			'Migration failed');
	});

	$('dup-go').addEventListener('click', async () => {
		const name = $('dup-name').value.trim();
		if (!name) return toast('Give the copy a name', true);

		const copy = await call(api.instance.duplicate(state.current.name, name), 'Could not duplicate');
		if (copy) {
			toast(`Duplicated as ${copy.name}`);
			await loadInstances();
			await openInstance(copy.name);
		}
	});

	$('exp-go').addEventListener('click', async () => {
		const path = $('exp-path').value.trim();
		if (!path) return toast('Say where the zip should go', true);

		const started = await call(api.instance.export(state.current.name, path, {
			includeConfig: $('exp-config').checked,
			includePacks: $('exp-packs').checked,
			includeWorlds: $('exp-worlds').checked,
		}), 'Export failed');
		if (started) toast('Exporting…');
	});

	$('instance-delete').addEventListener('click', () => {
		$('confirm-title').textContent = `Delete "${state.current.name}"?`;
		$('confirm-body').textContent =
			'The mod files stay in the shared store and the history is kept, so this can be '
			+ 'undone until you prune.';
		$('confirm-dialog').showModal();
	});

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

	$('vd-close').addEventListener('click', () => $('versions-dialog').close());
	$('sources-recheck').addEventListener('click', renderSources);

	$('cf-save').addEventListener('click', async () => {
		const key = $('cf-key').value.trim();
		if (!key) return toast('Paste a key first', true);

		const saved = await call(api.sources.setCurseForgeKey(key), 'Key rejected');
		// Cleared either way: a rejected key should not sit in the field waiting to be
		// resubmitted, and a saved one has no reason to stay on screen.
		$('cf-key').value = '';
		if (saved) { toast('CurseForge key saved'); renderSources(); }
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
 * Guarded because it is reached two ways -- the ready event and the health check below --
 * and whichever arrives second would otherwise reload everything and send the view back
 * to home, which is not harmless once someone has clicked into an instance.
 */
let started = false;
async function start() {
	if (started) return;
	started = true;

	applyAccent(stored('accent', ACCENTS[0].value));
	applyDensity(stored('density', 'comfortable'));
	renderAppearance();

	await loadInstances();
	show('home');
}

wire();

// The backend may already be up if this window reloaded, in which case the ready event
// has been and gone. A direct call settles which it is.
api.health().then((result) => { if (result.ok) start(); });
