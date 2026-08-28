// Builds the minimal Java runtime that ships inside the installer.
//
// Loadout's jar needs Java 21. A machine that has never run a Java program has none, and
// telling somebody to go and install a JDK before the installer will work is most of the
// reason people give up on a launcher. jlink cuts a runtime down to the modules the jar
// actually uses -- about 50 MB rather than the 300 MB a full JDK costs.
//
//   node scripts/runtime.mjs
//
// Not in git: it is a slice of whatever JDK built it, and would bloat every clone.

import { execFileSync } from 'node:child_process';
import { existsSync, rmSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const out = join(root, 'app', 'runtime');

// jdeps reports what the jar references. The two crypto modules are added by hand because
// TLS loads them as services rather than referencing them, so jdeps never sees them --
// and without them every HTTPS request fails at runtime rather than at build time.
const MODULES = [
	'java.base', 'java.compiler', 'java.desktop', 'java.net.http', 'java.sql',
	'jdk.httpserver', 'jdk.management',
	'jdk.crypto.ec', 'jdk.crypto.cryptoki',
	'jdk.zipfs', 'jdk.unsupported',
];

const home = process.env.JAVA_HOME;
if (!home) {
	console.error('JAVA_HOME is not set. Point it at a JDK 21 or newer.');
	process.exit(1);
}

const jlink = join(home, 'bin', process.platform === 'win32' ? 'jlink.exe' : 'jlink');
if (!existsSync(jlink)) {
	console.error(`No jlink at ${jlink}. JAVA_HOME must be a JDK, not a JRE.`);
	process.exit(1);
}

rmSync(out, { recursive: true, force: true });

execFileSync(jlink, [
	'--add-modules', MODULES.join(','),
	'--strip-debug', '--no-man-pages', '--no-header-files', '--compress=zip-9',
	'--output', out,
], { stdio: 'inherit' });

const java = join(out, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
const version = execFileSync(java, ['-version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
console.log(`runtime built at ${out}`);
console.log(version.trim().split('\n')[0]);
