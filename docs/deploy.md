# Deploying the servers

Two VMs, one pattern. Each server is a directory owned by `trent` holding a run script, a `.env`,
and the jar, driven by a systemd unit that runs the script in a restart loop. On every restart the
script pulls the newest jar CI published to the GitLab package registry (`darkanrs/server`,
project `67255206`), so **deploying is: push to the branch, wait for the pipeline, restart the unit.**
A failed download keeps the current jar running.

| VM | Host | Units |
|---|---|---|
| `darkan-lobbies` | `10.69.69.22` | `prod-lobby` (43594 / API 4040), `dev-lobby` (43593 / API 4039) |
| `darkan-worlds` | `10.69.69.21` | `bleeding-edge-world` (dev, world 1, 43601) + the production worlds |

CI (`.gitlab-ci.yml`) - `dev` is the only branch; releases are tags cut from it:

- `build` compiles every branch and tag and keeps nothing.
- `deploy` on a `dev` push publishes `org/darkan/{lobby,world}-server` as `3.0.0-dev-<pipeline id>` -
  the channel the beta scripts and the servers' auto-update follow (`AUTO_UPDATE_CHANNEL`, default
  `dev`, highest pipeline id wins).
- `deploy` on a tag publishes the same two packages under the tag, and `release` creates a GitLab
  Release named after the tag with a download link per component. The `run-*-release.sh` scripts pull
  the newest release's jar; `AUTO_UPDATE_CHANNEL=release` makes a server restart onto new releases
  automatically, through the same countdown.

## Layout of a server directory

Each directory is a shallow checkout of `dev` (`git clone --depth 1 -b dev git@gitlab.com:darkanrs/server.git <name>`)
plus the untracked `.env` and jar; the run script's `git pull origin dev` keeps scripts and `data/`
current. The repository is private, so the `trent` user on the VM needs read access: a per-host
read-only deploy key (`ssh-keygen -t ed25519` as `trent`, registered under the project's deploy
keys) is what `darkan-worlds` uses.


```
/home/trent/<name>/
  run-lobby-beta.sh | run-world-beta.sh   # from the repo root; -release variants follow tagged releases
  .env                                    # all configuration (see below); chmod 600
  lobby-server.jar | world-server.jar     # replaced by the script on restart
  mainlog.txt
  data/  plugins/                         # world only - the repo's data/ and plugins/ trees
```

The unit puts a JDK 25 first on `PATH` for that unit only, so the machine default (and every other
unit) is unaffected:

```ini
[Service]
Environment=DARKAN_JAVA_VM_ARGS="..."
Environment=GITLAB_ACCESS_TOKEN=...
Environment=DARKAN_GITLAB_PROJECT_ID=67255206
Environment=PATH=/home/trent/.sdkman/candidates/java/25.0.4-tem/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=/home/trent/<name>/run-<lobby|world>-beta.sh
WorkingDirectory=/home/trent/<name>
User=trent
```

JDK 25 was installed with sdkman as `trent` (`yes n | sdk install java 25.0.4-tem` - the `n`
declines making it the default; verify `readlink ~/.sdkman/candidates/java/current` is unchanged).

## `.env` - lobby

```env
DEBUG=true
SERVER_NAME=Darkan Dev
LOBBY_PORT=43593
LOBBY_API_PORT=4039
CACHE_PATH=../dev-cache/
MONGO_URI=mongodb://dkadmin:<pw>@10.69.69.11:27017/<db>?authSource=<db>&retryWrites=true&w=majority
LEGACY_CRYPTO_SECRET=<from the legacy lobby's data/crypt.json "secret">
LEGACY_CRYPTO_SALT="<crypt.json "salt" ints, comma separated>"
SOCIAL_GATEWAY_TOKEN=<shared with the worlds>
WORLD_LOGIN_TOKEN_SECRET=<shared with the worlds>
API_KEY=<admin API key>
LOCAL_WORLD_ID=300              # dev lobby only: lists world 300 as localhost:43595 for developers' own worlds
```

## `.env` - world

```env
DEBUG=true
SERVER_NAME=Darkan BE
CACHE_PATH=../cache/
MONGO_URI=<same database as the lobby>
WORLD_ID=1
WORLD_NAME=Darkan BE
WORLD_PORT=43601                 # must match worldInfo.port in data/worldConfig.json (the bind port)
WORLD_HOST=10.69.69.21
WORLD_PUBLIC_HOST=xmr.darkan.org # what the lobby advertises to clients
WORLD_ACTIVITY=Bleeding-Edge Test World
WORLD_MEMBERS=true
WORLD_BOTS_ALLOWED=false
WORLD_LOOTSHARE=true
WORLD_COUNTRY=USA
SOCIAL_GATEWAY_URL=ws://10.69.69.22:4039/social/ws
SOCIAL_GATEWAY_TOKEN=<same as lobby>
WORLD_LOGIN_TOKEN_SECRET=<same as lobby>
```

`data/worldConfig.json` keeps the world's gameplay settings (name, world info, start tiles, xp
rate, start items). Its old `mongo*` keys are unused; `MONGO_URI` is the only database setting.

RSA keys and the JS5/world tokens default to the values the client was built with (the same
constants the legacy servers compiled in), so they need no entries unless they change.

## Cutting a lobby + world over from legacy (what was done for dev, 2026-08-29)

1. `systemctl stop <lobby-unit>` and `systemctl stop <world-unit>` for the pair being replaced only.
2. Create the database user: a `dkadmin` scoped to the new database (`dbOwner` on it), so the VMs
   never hold admin credentials.
3. Migrate, from a machine with the `migration/` module and admin access to Mongo
   (see `docs/migration.md`):
   ```bash
   ./gradlew :migration:run --args="migrate --source '$OLD' --target '$NEW' --replace"
   MONGO_URI="$NEW" ./gradlew :migration:run --args="verify --target '$NEW'"
   ```
   Both must finish "with no problems". The source database is never written; it is the rollback.
4. Move the legacy directory aside (`mv <name> <name>-legacy-backup-<date>`), keep a copy of the
   unit file next to it, and create the new directory with the script, `.env` and a bootstrap jar
   (`./gradlew :lobby:shadowJar :world:shadowJar` locally and `scp`, until CI has published one).
   For a world, keep `data/` and `plugins/` from the legacy checkout and strip the `mongo*` keys
   from `data/worldConfig.json`.
5. Write the unit (above), `systemctl daemon-reload`, start the lobby, then the world.
6. Verify - all from the lobby VM:
   - `grep 'Lobby server started' mainlog.txt`; `curl -s localhost:<api>/health` → 200
   - world log: `World server started` and `SocialClient connected`
   - lobby log: `SocialGateway: world connected worldId=<n>`; `curl -s localhost:<api>/worldlist`
   - a JS5 handshake (`[15][len][6][727][1][token\0]` and without the `6`) answers `0` (JS5_SYNC)

### Rollback

Stop the unit, restore the backed-up unit file, `mv` the legacy directory back, `daemon-reload`,
start. The legacy database was never modified.

## Automatic updates (dev servers)

The `run-*-beta.sh` scripts start the server with `AUTO_UPDATE=true` (set `AUTO_UPDATE=false` in the unit's environment to opt out; the release scripts never set it). With it on, a server polls the registry (`AUTO_UPDATE_POLL_SECONDS`, default
60) for a `latest` newer than the `BUILD_VERSION` the run script started it with. When one appears:

- **world** - the same countdown an admin's `::update` starts: `SystemUpdate` timer on every client
  for `AUTO_UPDATE_COUNTDOWN_MINUTES` (default 5), then every player is saved and logged out and the
  process exits;
- **lobby** - logins are refused with "server being updated" for the final 20 ticks, then a clean stop;
  worlds reconnect to it automatically.

The run script's loop then downloads the new jar and restarts. So a deploy to dev is just: push to
`dev`, wait for the pipeline. A server started from a local jar (blank `BUILD_VERSION`) updates to
the first published build. Production units run the release scripts, which never enable it; they restart on purpose.

## Notes from the dev cut-over

- `data/world.json` (persisted world state: counters, penguin hide-and-seek, polar bear) carries over
  as-is; the world reads and rewrites it through the world Gson (`TileAdapter`), so tiles persisted by
  the legacy server load correctly. Content must never use a bare `Gson()` for anything holding a
  `Tile` - the value class has no `x`/`y` fields for reflection to find, and every tile decodes as (0, 0).
- An entity at an off-map coordinate used to throw from the pathfinder every tick it tried to move;
  off-map now reads as blocked, and an NPC spawned in the (0..31, 0..31) corner logs a warning naming
  it and its spawner so the cause is visible in `mainlog.txt`.
- The `localhost` world (300) for developers' own machines is opt-in via `LOCAL_WORLD_ID`, not a `DEBUG` side effect.
- The lobby's Netty engine logs `RejectedExecutionException: event executor terminated` while a
  restart tears it down; that is shutdown noise, not an error in our code.

## The cache source tree

The game cache is no longer a git-lfs blob: `cache-source/` is a git submodule holding the whole
727 cache unpacked (`docs/cache-source.md`), and the gameval catalogs live inside it
(`cache-source/gamevals/`) - there is no other copy, nothing is bundled into the jars. A server
needs that directory on disk for names to resolve, and can build and serve the cache from it.

**On each host, once** (as `trent`, in the server checkout):

```bash
git submodule update --init --depth 1 cache-source   # a few GB the first time; shallow
```

The run scripts repeat that after their `git pull`, so the tree follows the commit the checkout
pins. Then in `.env`:

```env
CACHE_SOURCE_PATH=./cache-source   # build the cache from the tree on startup and serve it
CACHE_BUILD_PATH=./data/cache      # where the packed build lives (gitignored; default)
# CACHE_PATH is ignored while CACHE_SOURCE_PATH is set. To keep serving a prepacked cache
# instead, leave CACHE_SOURCE_PATH unset - the tree is still needed for GAMEVAL_PATH.
```

The first start packs the whole cache (a minute or two on 20 cores; ~900 MB under `data/cache/`).
Every later start stats the tree and repacks only what changed, so an unchanged tree costs about a
second. `data/cache/manifest.json` records what the build was made from; delete the directory to
force a full rebuild.

Disk: the tree is a few GB plus the packed build. The world host is memory-tight; the build runs
inside the server JVM at startup, so keep `-Xmx` at least 3 GB on a host that builds.

**CI** (`.gitlab-ci.yml`) does not check the submodule out: it sparse-clones only
`cache-source/gamevals` at the pinned commit (blob-less clone, a few MB), which is all the typed
constants generator needs. Locally, `git clone --recurse-submodules` or the `submodule update`
above; `-Pdarkan.cacheSource=<path>` / `DARKAN_CACHE_SOURCE` point the build at a tree elsewhere.

**Rollout order** (the catalogs moved out of the server repo, so the two repos must move together):

1. The tree lives at `darkanrs/cache-unpacked` (checked out as `cache-source/`). The submodule URL in
   `.gitmodules` is relative (`../cache-unpacked.git`), so it resolves against the server repo's own
   remote; `tools cache unpack` regenerates the tree from a packed cache (`docs/cache-source.md`).
2. Push the server branch. CI's sparse clone needs the cache-source project to allow CI job tokens
   from the server project (Settings → CI/CD → Token Access).
3. On the two dev hosts run the `submodule update` above, add the `.env` lines, restart the units.

## Lobby commands and password recovery

The lobby accepts `::` commands from a logged-in client (typed as a command or as `::`/`;;` chat),
dispatched by rights the same way the world does (`org.darkan.lobby.command.LobbyCommands`):

| command | rights | effect |
|---|---|---|
| `::commands` | player | lists what you can run |
| `::changepass <new password>` | player | changes your own password |
| `::changepassother <player name> <new password>` (aliases `setpass`, `resetpass`) | owner | account recovery: sets another player's password |

Rights alone do not unlock the administrative tiers. The owner tier is reserved for the account named by
`LOBBY_OWNER` in `.env` (default `trent`), and while `DEBUG=true` every tier above player is reserved
for it too: a dev environment lets people grant themselves admin or owner rights on their own hosted
worlds, so the lobby trusts the configured name, not the crown. Refused attempts are logged.

Passwords must be at least 8 characters of letters and digits. A change writes a fresh Argon2id
hash, drops any legacy AES or SHA-1 credential so the login path cannot fall back to the old
secret, and is saved immediately; the player can log in with it on their next attempt. Command
lines are logged without the password.

## Production

Same steps against `prod-lobby` and each production world, with the production database as the
source and a new database as the target - after the dev pair has soaked. Do worlds one at a time;
each one's `SOCIAL_GATEWAY_URL` points at the production lobby's API port and shares its tokens.
Re-run `migrate`/`verify` immediately before starting the new lobby so nothing written in between
is lost.
