'use strict';

const { contextBridge, ipcRenderer } = require('electron');

/**
 * The only thing the page can reach outside itself.
 *
 * Written out as named methods rather than a general "send anything" bridge. The point of
 * context isolation is that the page gets a fixed surface, and a bridge that forwards
 * arbitrary channels gives that back immediately -- the page could then reach any handler
 * the main process happens to register, including ones added later for something else.
 *
 * Every call resolves to {ok, data} or {ok, error}, so the caller handles a failure the
 * same way whether it came from the network, the jar, or a bad argument.
 */
const invoke = (channel, ...args) => ipcRenderer.invoke(channel, ...args);

contextBridge.exposeInMainWorld('loadout', {
	health: () => invoke('health'),

	sources: {
		list: (verify) => invoke('sources:list', verify),
		setCurseForgeKey: (key) => invoke('settings:curseforgeKey', key),
	},

	profiles: {
		list: () => invoke('profiles:list'),
		get: (name) => invoke('profiles:get', name),
		create: (profile) => invoke('profiles:create', profile),
		remove: (name) => invoke('profiles:delete', name),
	},

	mods: {
		install: (profile, source, id, type) => invoke('mods:install', profile, source, id, type),
		remove: (profile, fileName) => invoke('mods:remove', profile, fileName),
		toggle: (profile, fileName, enabled) => invoke('mods:toggle', profile, fileName, enabled),
		icons: (profile) => invoke('mods:icons', profile),
		installVersion: (profile, source, id, versionId, type) =>
			invoke('mods:installVersion', profile, source, id, versionId, type),
	},

	instance: {
		worlds: (profile) => invoke('instance:worlds', profile),
		servers: (profile) => invoke('instance:servers', profile),
		logs: (profile) => invoke('instance:logs', profile),
		screenshots: (profile) => invoke('instance:screenshots', profile),
		logTail: (profile, log) => invoke('instance:logTail', profile, log),
		packs: (profile, type) => invoke('instance:packs', profile, type),
		duplicate: (profile, target) => invoke('instance:duplicate', profile, target),
		export: (profile, path, options) => invoke('instance:export', profile, path, options),
	},

	snapshots: {
		list: (profile) => invoke('snapshots:list', profile),
		rollback: (profile, snapshotId) => invoke('snapshots:rollback', profile, snapshotId),
	},

	migrate: (profile, target, apply, includeLikely) =>
		invoke('migrate', profile, target, apply, includeLikely),

	launch: (profile, username) => invoke('launch', profile, username),
	search: (query) => invoke('search', query),
	versions: (source, id, profile) => invoke('versions', source, id, profile),
	java: () => invoke('java:list'),

	options: {
		get: (profile) => invoke('options:get', profile),
		set: (profile, options) => invoke('options:set', profile, options),
		defaults: () => invoke('options:defaults'),
		setDefaults: (options) => invoke('options:setDefaults', options),
	},
	minecraftVersions: () => invoke('minecraft:versions'),

	jobs: {
		list: () => invoke('jobs:list'),
		cancel: (id) => invoke('jobs:cancel', id),
	},

	openExternal: (url) => invoke('open:external', url),
	openPath: (target) => invoke('open:path', target),

	/**
	 * Job progress, pushed rather than polled.
	 *
	 * Only the event payload is passed on. Handing the renderer the IpcRendererEvent would
	 * hand it a sender it could reply through, which is exactly the kind of reference
	 * context isolation exists to withhold.
	 */
	onJobEvent: (callback) => {
		const listener = (_event, payload) => callback(payload);
		ipcRenderer.on('job:event', listener);
		return () => ipcRenderer.removeListener('job:event', listener);
	},

	onReady: (callback) => {
		const listener = () => callback();
		ipcRenderer.on('backend:ready', listener);
		return () => ipcRenderer.removeListener('backend:ready', listener);
	},
});
