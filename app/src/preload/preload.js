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
		install: (profile, source, id) => invoke('mods:install', profile, source, id),
		remove: (profile, fileName) => invoke('mods:remove', profile, fileName),
		toggle: (profile, fileName, enabled) => invoke('mods:toggle', profile, fileName, enabled),
	},

	snapshots: {
		list: (profile) => invoke('snapshots:list', profile),
		rollback: (profile, snapshotId) => invoke('snapshots:rollback', profile, snapshotId),
	},

	migrate: (profile, target, apply, includeLikely) =>
		invoke('migrate', profile, target, apply, includeLikely),

	launch: (profile, username) => invoke('launch', profile, username),
	search: (query) => invoke('search', query),
	java: () => invoke('java:list'),

	jobs: {
		list: () => invoke('jobs:list'),
		cancel: (id) => invoke('jobs:cancel', id),
	},

	openExternal: (url) => invoke('open:external', url),

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
