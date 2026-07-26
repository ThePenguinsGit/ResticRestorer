# ResticRestorer

Something ate something it shouldn't have. Could have been a player, a funny mod update, or a guest with an
unnamed explosive device and opinions.

Luckily you use [restic](https://restic.net/) and run regular backups, but restoring them kinda sucks.

**Without this mod, you have to:**

1. find the right snapshot
2. get the path for the right `.mca` region file
3. download it somewhere
4. figure out whether other stuff in that region is unaffected and shouldn't be reset to whatever time that
   backup was taken
5. (if there is) go into the deep end and whip out a region editor and _somehow_ merge the old (not yet broken)
   chunk into the current region
6. shut down the server
7. move the fixed region file into place
8. start it up

**With this mod:**

1. find the right snapshot (you get autocompletion in the chat)
2. run the restore command

> Oh, also figure out who to punish for doing that, but that's beside the point.

This also works in case your player (or tomb/grave mod) fucks up and you need to restore an inventory:

1. figure out the time
2. run the command

**Nothing needs a restart, nothing needs a rejoin.** ResticRestorer just does it.

## What you need

A restic repository that already has backups of your world in it. Just make sure your Minecraft server can
either read the directory, or call the server where your repository is.

You also need [Architectury API](https://modrinth.com/mod/architectury-api) on the server, plus
[Fabric API](https://modrinth.com/mod/fabric-api) if you're on Fabric.

| Minecraft | Loaders                        | Java | Architectury API |
|-----------|--------------------------------|------|------------------|
| 1.20.1    | Fabric, Forge 47.3.10 or newer | 17   | 9.2.14 or newer  |
| 1.21.1    | Fabric, NeoForge 21.1 or newer | 21   | 13.0.8 or newer  |

## Getting set up

1. Drop the jar for your version and loader into `mods`, along with Architectury API.
2. Start the server once. You'll get a `config/resticrestorer.json` full of defaults, plus a log line saying
   `ResticRestorer initialized (backup provider: cli)`.
3. Fill in the config, mainly your repository and password.
4. Run `/restore-reload` to pick up the changes without a restart.

## Restoring a chunk

```
/restore-chunk 42 -17 2026-03-04T19-00-00
```

Those two numbers are **chunk** coordinates, not block coordinates. Press F3 while standing in the chunk you
want and read them off the "Chunk" line. Tab completion on the third argument lists the backup times the mod
knows about.

The command replies immediately and works in the background. When it's done you'll either get a confirmation,
or a message telling you what went wrong.

> **Make sure players aren't near it**, since the chunk needs to be unloaded to get replaced. ResticRestorer
> will wait up to 10 seconds for the chunk to become unloaded. If that's not enough it'll tell you, and you can
> rerun the command to try again.

For a player:

```
/restore-player Notch 2026-03-04T19-00-00
```

If they're online, their data goes straight into their player entity and the client is resynced, which also
sends them back to the location they were at when the backup was taken. No need to have them disconnect first.

## Snapshots

The snapshot argument takes a **backup time** like `2026-03-04T19-00-00`, in UTC. You don't have to name a
backup time exactly, the mod takes the newest snapshot at or before the time you gave, so "sometime around
seven, it was still fine then" is a perfectly good way to use it.

It also takes a **restic snapshot id** or short id, like `ecda1097`. Anything that isn't a valid time is
treated as an id.

Tab completion shows you a list of snapshot times that are in your repository. By default that list is
refreshed every 30 minutes, and if that's too long for you, you can change it in the config.

## Configuration

Everything lives in `config/resticrestorer.json`. Here's what you start with:

```json
{
  "backup": {
    "provider": "cli",
    "restic": {
      "binary": "restic",
      "repository": "",
      "password": "",
      "extraArgs": []
    },
    "rest": {},
    "snapshotWorldPrefix": "/world",
    "serverWorldPrefix": "",
    "snapshotCacheRefreshMinutes": 30
  },
  "world": {
    "defaultDimension": "minecraft:overworld"
  },
  "rabbitmq": {
    "enabled": false,
    "host": "localhost",
    "port": 5672,
    "virtualHost": "/",
    "username": "guest",
    "password": "guest",
    "queue": "resticrestorer.restore",
    "prefetch": 1
  },
  "restore": {
    "maxConcurrent": 1
  }
}
```

Some optional settings aren't in that file because they have no value yet. They're described below, and you add
them by hand.

### backup

`provider` selects the backend. Leave it on `"cli"`, which runs the restic binary. The `"rest"` provider and
the empty `rest` block are groundwork for talking to a REST API directly later on.

`restic.binary` is how the mod finds restic: a full path like `/usr/local/bin/restic`, a bare `restic` if it's
on the server's `PATH`, or `"bundled"`. That last one unpacks a restic binary from inside the mod jar, which
saves you a package install in a locked down container. It only covers Linux on amd64 and arm64, so on Windows
or macOS install restic yourself and point this at it.

`restic.repository` goes to restic untouched, so use whatever you'd type on the command line. A local path like
`/srv/restic-repo` works, and so does a REST server like `rest:http://backup-host:8000/`.

`restic.password` is the repository password. If you'd rather not have it sitting in this file, delete the key
and add `"passwordFile": "/run/secrets/restic-password"` instead. If both are there, the file wins.

`restic.extraArgs` is a list of flags added to every restic call, for the occasional `--no-cache` or
`--limit-download`.

`restic.hostFilter` and `restic.tagFilter` matter when several servers share a repository. Add something like
`"tagFilter": "plan:my-survival-server"` and the mod only sees that server's snapshots. Without it you might
get offered a different server's backup, which is a memorable way to restore the wrong chunk.

`snapshotWorldPrefix` is where the world directory sits **inside the snapshot**, and it's the setting people
get wrong. It has to match how the world was backed up, not where it lives now. If your backup runs in a
container against `/data/world`, that's what goes here, even though the server sees the world somewhere else.
When in doubt, ask restic:

```
restic ls latest | head
```

The prefix the region files sit under is the one you want.

`serverWorldPrefix` can stay empty. Empty means "same as `snapshotWorldPrefix`", which is right for a normal
restic restore. It only exists for the rare setup where restored files land somewhere other than the path they
were matched by.

`snapshotCacheRefreshMinutes` is how often the snapshot list behind tab completion gets rebuilt, in minutes.
Thirty is fine for most people. Lower it if your backups run often and you want fresher suggestions.

### world

`defaultDimension` is the dimension used when nothing else says otherwise: `/restore-chunk` without its
optional dimension argument, and every RabbitMQ message. Modded dimension ids are fine here.

### rabbitmq

Off unless you turn it on. Set `"enabled": true` and fill in the connection details if you want a panel, a bot
or a web UI to request restores. Messages on `queue` look like this:

```json
{ "x": 42, "y": -17, "snapshotId": "ecda1097" }
```

`x` and `y` are chunk coordinates. `y` is the chunk **Z**, named that way for the sending side's convenience.
`snapshotId` is a restic id or short id and it's required. These restores always use `world.defaultDimension`.

A message is acknowledged as soon as the restore starts. Anything malformed gets rejected without requeueing,
so one bad message can't spin forever, and the reason lands in the log along with the result of every restore.

`prefetch` caps how many messages the broker hands over before it gets an acknowledgement. One keeps things
sequential.

### restore

`maxConcurrent` is how many restores run at once. The default of 1 is usually right, since they all queue up on
the server thread for the final swap anyway.

`tempDir` isn't in the generated file, which means the system temp directory gets used. Add
`"tempDir": "/srv/restore-scratch"` if your temp space is small or slow. Either way the fetched files are
cleaned up afterwards.

## Commands and permissions

| Command                                         | What it does                    | Permission node                        |
|-------------------------------------------------|---------------------------------|----------------------------------------|
| `/restore-chunk <x> <z> <snapshot> [dimension]` | Restores one chunk              | `resticrestorer.command.restorechunk`  |
| `/restore-player <name or uuid> <snapshot>`     | Restores one player's data file | `resticrestorer.command.restoreplayer` |
| `/restore-reload`                               | Re-reads the config file        | `resticrestorer.command.reload`        |

Permissions go through whatever permission mod you already run. Fabric uses `fabric-permissions-api`, which
LuckPerms hooks into automatically and which is bundled in the jar. Forge and NeoForge register the nodes with
their own permission API. With no permission mod installed, the commands need op level 3.

`/restore-reload` reads and validates the new config before swapping anything in. If the file doesn't parse it
says so and keeps the old settings running.

## How a chunk restore actually works

Worth knowing, if only because it explains the "move players away" part.

The fetch happens in the background. The mod asks restic for the handful of files that chunk needs, unpacks
them into a temp directory and parses them there. The server ticks along normally the whole time.

The swap then happens on the server thread all at once. If the chunk was forceloaded, that's remembered. The
chunk unloads, the region, entity and POI data get written, and the chunk comes back with its forceload ticket
restored. If it can't unload because someone is standing there, the whole thing gives up cleanly after about
ten seconds without touching the world.

Reading everything through Minecraft's own storage classes is deliberate. It means oversized chunks living in
`.mcc` files and whatever compression your modpack uses are handled exactly the way the game handles them.

Player restores are simpler: the `.dat` file gets replaced, and an online player also gets it loaded live with
a client resync.

## When something goes wrong

**"No chunk data found in the fetched backup ..."** means restic ran fine but there was nothing where the mod
looked. That's `snapshotWorldPrefix` almost every time. The message names the directory it searched, so compare
that against `restic ls <snapshot>`.

**"No player data file for ... found in the fetched backup"** is the same thing for `/restore-player`, though
it can also mean the player didn't exist yet at that snapshot.

**A restic error.** The full command and restic's own output go to the server log, which usually points
straight at a wrong password, an unreachable repository or a missing binary. Running the same command by hand
as the server user is a good next step.

**Nothing in tab completion.** Give it a moment after startup. If it stays empty, look for
"Snapshot suggestion refresh failed" in the log, which carries the reason. Snapshot ids still work in the
meantime.

**"Restore already in progress".** One restore per chunk and per player at a time. Wait for the first to
report back.

## Repository layout

If you want to poke around the source:

* `core/` is the Minecraft free code: config, restic, RabbitMQ, and the restore pipeline.
* `shared/mc/` is the Minecraft facing code every supported version shares, mostly the commands and the chunk
  swap. `shared/fabric/` is the Fabric entrypoint both Fabric builds share.
* `common-1201/` and `common-1211/` hold the only version specific code, a small adapter each.
* `fabric-1201/`, `forge-1201/`, `fabric-1211/` and `neoforge-1211/` are thin per loader wiring.

`./gradlew build` builds all four jars into each loader module's `build/libs/`.
