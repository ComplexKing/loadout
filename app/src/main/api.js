'use strict';

const http = require('node:http');

/**
 * HTTP client for the jar's API.
 *
 * Lives in the main process, and deliberately not in the renderer. The renderer is the
 * half that renders text from mod listings -- descriptions, titles, author names written
 * by strangers -- so it is the half worth treating as untrusted. Keeping the token here
 * means a flaw in that rendering cannot reach the API, because the page never holds the
 * credential it would need.
 *
 * It is also simply required: the jar sends no CORS headers and refuses preflights, by
 * design, so a renderer could not call it directly even if we wanted that.
 */
class Api {
	constructor(port, token) {
		this.port = port;
		this.token = token;
	}

	request(method, path, body) {
		return new Promise((resolve, reject) => {
			const payload = body === undefined ? null : JSON.stringify(body);

			const request = http.request({
				host: '127.0.0.1',
				port: this.port,
				path,
				method,
				headers: {
					Authorization: `Bearer ${this.token}`,
					...(payload ? { 'Content-Type': 'application/json' } : {}),
				},
			}, (response) => {
				let text = '';
				response.setEncoding('utf8');
				response.on('data', (chunk) => { text += chunk; });
				response.on('end', () => {
					let parsed;
					try {
						parsed = text ? JSON.parse(text) : {};
					} catch {
						reject(new Error(`Unreadable response from ${path}`));
						return;
					}

					if (response.statusCode >= 400) {
						// The jar's own message is the useful one -- "no build for 1.21.4",
						// "that key was not accepted" -- so surface it rather than a status.
						const error = new Error(parsed.error || `HTTP ${response.statusCode}`);
						error.status = response.statusCode;
						reject(error);
						return;
					}

					resolve(parsed);
				});
			});

			// Node's own message for a dead backend is "connect ECONNREFUSED 127.0.0.1:52223",
			// which tells somebody using a launcher nothing they can act on. The cause is
			// always the same -- the jar this app spawned is no longer there.
			request.on('error', (error) => {
				if (error && (error.code === 'ECONNREFUSED' || error.code === 'ECONNRESET')) {
					reject(new Error('Loadout stopped responding. Restart the app.'));
					return;
				}
				reject(error);
			});
			if (payload) {
				request.write(payload);
			}
			request.end();
		});
	}

	get(path) {
		return this.request('GET', path);
	}

	/**
	 * Subscribes to job progress.
	 *
	 * Server-sent events over a connection that stays open, so this never polls. The
	 * stream is also how a job started before this window opened becomes visible: the jar
	 * replays anything still running when a client connects.
	 *
	 * @param onEvent called for each event
	 * @returns a function that closes the stream
	 */
	events(onEvent) {
		let closed = false;
		let request = null;
		let retry = null;

		const connect = () => {
			if (closed) {
				return;
			}

			request = http.request({
				host: '127.0.0.1',
				port: this.port,
				path: '/events',
				method: 'GET',
				headers: { Authorization: `Bearer ${this.token}`, Accept: 'text/event-stream' },
			}, (response) => {
				let buffer = '';
				response.setEncoding('utf8');

				response.on('data', (chunk) => {
					buffer += chunk;

					// Events are separated by a blank line; anything after the last one is
					// a partial event still arriving.
					const parts = buffer.split('\n\n');
					buffer = parts.pop();

					for (const part of parts) {
						const line = part.split('\n').find((l) => l.startsWith('data: '));
						if (!line) {
							continue;   // a keepalive comment
						}
						try {
							onEvent(JSON.parse(line.slice(6)));
						} catch {
							// A malformed event should not tear down the stream.
						}
					}
				});

				response.on('end', () => scheduleRetry());
			});

			request.on('error', () => scheduleRetry());
			request.end();
		};

		const scheduleRetry = () => {
			// The jar restarting, or a dropped connection, should not silently stop
			// progress from ever appearing again.
			if (!closed && retry === null) {
				retry = setTimeout(() => { retry = null; connect(); }, 1000);
			}
		};

		connect();

		return () => {
			closed = true;
			if (retry !== null) {
				clearTimeout(retry);
			}
			if (request) {
				request.destroy();
			}
		};
	}
}

module.exports = { Api };
