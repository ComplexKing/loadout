# Loadout

A Minecraft profile manager and launcher for Java Edition, focused on the thing other
launchers handle worst: **moving a set of mods from one Minecraft version to another.**

> Early but working. Installs and launches Minecraft with Fabric, signs in with a
> Microsoft account, migrates profiles between versions against the live Modrinth and
> CurseForge APIs, and ships a desktop interface and a companion mod that manages mods
> from inside a running game.

## The problem it solves

Every launcher will update your mods one at a time. None of them will tell you, up front,
that moving your profile to 1.21.1 is impossible because two mods out of eighty never
shipped a build for it — which is the only question that actually matters before you
start.

```
$ loadout migrate main 1.21.1

Would update (5)
  Fabric API                    0.154.2+26.2   -> 0.116.15+1.21.1
  Mod Menu                      20.0.1         -> 11.0.4
  Sodium                        0.9.2-alpha.4  -> mc1.21.1-0.8.13-beta.2-fabric

Likely matches - confirm these (1)
  YetAnotherConfigLib           3.9.4+26.2     -> 3.8.2+1.21.1-fabric

Not on Modrinth (1)
  Bask                          bask-0.1.0.jar

1 mods would block the move to 1.21.1.
```

Mods are identified by **SHA-512 hash**, so renamed files, browser `(1)` suffixes and
version collisions don't matter. When a hash isn't recognised — which happens to anything
downloaded from CurseForge, a GitHub release or a Maven repo, since those aren't the
bytes Modrinth hosts — it falls back to matching by identity, and reports those separately
as needing confirmation rather than acting on a guess.

## How it's built

Mods live in a **content-addressed store** and profiles hold **hard links** into it.
Several features fall out of that one decision:

- **Deduplication.** Ten profiles using Sodium reference one file on disk.
- **Instant offline rollback.** A snapshot is a list of hashes, so undoing a migration is
  relinking rather than re-downloading — and takes under a second.
- **Nothing half-written.** Files land under their own hash, so a file that exists is a
  file that verified.

Minecraft, Fabric and asset metadata come **straight from Mojang's and FabricMC's own
endpoints**. No third-party metadata service sits in the middle, so nothing goes stale on
a new release and nobody else is in a position to hand users a modified library list.

## Safety choices

- **Import copies, never moves.** Point it at your live `.minecraft` and you keep a
  working game.
- **A snapshot is taken before every destructive operation**, including before a rollback,
  so undoing is itself undoable.
- **Everything downloads and verifies before anything is rewritten.** A failure halfway
  leaves the profile untouched.
- **Blockers stop a migration.** Moving a profile while a mod has nowhere to go leaves it
  on the old version, which usually means the game won't start.
- **Pruning is explicit, never automatic.** Free rollback stops being true the moment a
  tool discards the version you were about to return to.
- **Access tokens are redacted from logs and crash output** before anything reaches disk.
  Minecraft is launched with its token on the command line, and people paste whole logs
  into chat when asking for help.
- **Offline play requires an account that has signed in with Microsoft before.** There is
  no way to launch from a bare username.

## Commands

```
scan <mods-dir>                     list what's in a folder
plan <mods-dir> <mc-version>        report on a folder without importing it

import <mods-dir> <profile> <mc-version> [loader]
list
migrate <profile> <mc-version> [--apply] [--include-likely]
snapshots <profile>
rollback <profile> <snapshot-id>
sync <from-profile> <to-profile> [--overwrite]
prune

java                                list Java installations found
install <mc-version> [loader]       download Minecraft, Fabric and assets
run <profile> [username]            launch a profile
```

## CurseForge

Modrinth works with no setup. CurseForge needs an API key from
[console.curseforge.com](https://console.curseforge.com):

```bash
loadout key curseforge
```

Run it with no key and it prompts, with the input hidden. Pass the key as an argument
instead and your shell will eat it: CurseForge issues keys in the bcrypt style, so an
unquoted `$2a$10$...` is three variable expansions and arrives truncated. The prompt also
keeps the key out of your shell history and out of the process argument list, which other
processes can read.

The key is checked against CurseForge before it is stored, so a bad one is refused rather
than saved and rejected on every later request.

## Download

[**Loadout 0.1.0**](https://github.com/ComplexKing/loadout/releases/latest) — the Windows
installer bundles a Java runtime, so there is nothing to set up first. The jar is there
too for macOS, Linux and the command line, and needs Java 21 or newer.

The [companion mod](https://github.com/ComplexKing/loadout-mod/releases/latest) is a
separate download.

## Installing

```bash
./gradlew dist
```

That produces `build/dist/` — `loadout.jar` with a `loadout` shim beside it. Put the
shim on your PATH so the `loadout ...` commands above work as written:

```powershell
.\build\dist\install.ps1
```

On Linux and macOS, symlink or add the folder to `PATH` yourself:

```bash
ln -s "$PWD/build/dist/loadout" ~/.local/bin/loadout
```

Without the shim on PATH, every command still works spelled out in full:

```bash
java -jar build/dist/loadout.jar sources
```

## Building

```bash
./gradlew build
```

Java 21 or newer. The launcher targets 21 deliberately — Minecraft 26.1+ needs Java 25,
but someone whose Java is out of date is exactly the person who needs a launcher to sort
it out for them. Loadout finds and selects a suitable JDK per version itself.

Set `LOADOUT_HOME` to relocate its data directory; it defaults to `~/.loadout`.

## API

`loadout serve` exposes the same core over HTTP on 127.0.0.1, so a desktop UI can drive
it without reimplementing anything. It prints its port and access token as one line of
JSON, then runs until stopped:

```json
{"ready":true,"port":47399,"token":"...","gameToken":"...","pid":24844}
```

Every request needs `Authorization: Bearer <token>`. Long operations — install, migrate,
launch — return a job id immediately; `GET /events` streams their progress as
server-sent events.

The server is loopback-only, sends no CORS headers, and checks the `Host` header, so a
web page cannot reach it even while the launcher is running.

### Two tokens

There are two, and the second is the interesting one. `gameToken` is handed to the game
Loadout starts, so the [companion mod](https://github.com/ComplexKing/loadout-mod) can
manage mods from inside a session. Anything in that JVM can read a system property, so
whatever goes there has to be something it is acceptable for *every* mod in the pack to
hold — which means it reaches a deliberately small set of endpoints and nothing else:

| The game token can | It cannot |
| --- | --- |
| read this instance, search, install | touch accounts |
| turn mods on and off for next launch | change settings |
| report that the client finished starting | delete anything |
| start a successor to itself, to apply a change | |

Both are compared in constant time, and both are always checked, so timing cannot
distinguish which one was wrong.

## When a game stops starting

Adding a mod and finding the game will not start is the failure this launcher is most
able to help with, because it knows something the crash log does not: which mod set
worked last time.

The companion mod reports in once the client is up — Fabric resolved the list, every mixin
applied, the window exists. Nothing outside the process can tell that apart from a game
that died before the window opened; both look like a process that was running and then was
not. The first time a given set reports in, a snapshot is taken.

So when a launch exits badly inside the first minute without ever reporting, the interface
says so and offers the last set that did start. It offers; it does not act. Rewriting
somebody's mod list because a process exited badly is help that is indistinguishable from
a bug, and the diagnosis is a good guess rather than a fact.

It also stays quiet when it should. A game that ran for an hour and then crashed *did*
start, so its mods are not the answer. And a failed start with the same mods that worked
last time says exactly that, rather than offering a rollback that would change nothing and
send somebody looking in the wrong place.

## Desktop app

An Electron front end lives in `app/`. It spawns the jar, reads the port and token from
its handshake, and drives the API — so the logic has one implementation and the CLI stays
a first-class way to use it rather than a stripped-down mode.

```bash
cd app && npm install && npm start
```

Needs `gradlew dist` to have been run first, since it looks for `build/dist/loadout.jar`.

Two flags help when working on the interface. `--dev` forwards the page's console to the
terminal, and `--screenshot=<path>` renders the window to a PNG and exits, optionally
after `--tab=browse --query=shader` — so a layout can be reviewed without a window
repeatedly taking over the screen:

```bash
npm start -- --dev --open="my instance" --tab=add --query=shader --screenshot=out.png
```

`--view`, `--click` (several selectors separated by `|`) and `--eval` reach states that
need more than one step, such as a dropdown inside a dialog.

The renderer holds no API token. It talks to the main process over a fixed set of named
IPC channels, and the main process holds the credential — which matters because the page
renders titles and descriptions written by strangers on two public registries.

## Not done yet

- **In-game browsing.** The companion mod lists and toggles what is installed, but adding
  something new still means the launcher window.
- **Rejoining a Realm.** Quick play can reopen one, but by realm id rather than anything
  the client hands out, and a rejoin that opens the wrong world is worse than one that
  admits it cannot.
- **Shader packs**, which are a loader's concept rather than the game's — and the loader
  that reads `shaderpacks/` does not run on 26.2's Vulkan path at all. Loadout installs
  them into the folder for whenever that changes.

## Website

A single static page lives in `site/`. No build step, no dependencies — open
`site/index.html`, or point GitHub Pages at the folder.

## Licence

MIT. Not affiliated with Mojang Studios or Microsoft.
