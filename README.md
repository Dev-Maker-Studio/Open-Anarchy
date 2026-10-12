## Running

### Lobby (includes SocialGateway + HTTP API + lobby login server)

```bash
./gradlew :lobby:run
```

### World

```bash
./gradlew :world:run
```

### Smoke test

`./smoke-test.sh` builds both servers, boots them against the database in `.env`, waits for the
world to register with the lobby gateway and checks `/health`. See the script header for running
it alongside the legacy servers on other ports.

### Migrating a legacy database

See `src/engine/migration.md` (`migration/` module: `inspect`, `migrate --dry-run`, `migrate`, `verify`).

## SocialGateway (world <-> lobby)

The lobby HTTP server exposes a persistent WebSocket endpoint for world-servers:

- `ws://<lobby-host>:<LOBBY_API_PORT>/social/ws`

World-servers connect and authenticate with `SOCIAL_GATEWAY_TOKEN`, then send presence + social events.
With no configuration a world joins the dev lobby at `dev.darkan.org` as a community world; see
`src/engine/community-worlds.md` to run your own.

### Environment variables

```bash
# Existing
MONGO_URI=mongodb://localhost:27017/darkan-server?retryWrites=true&w=majority
LOBBY_API_PORT=4040
API_KEY=TEST_API_KEY
SERVER_NAME=Darkan

# World <-> lobby (defaults shown)
SOCIAL_GATEWAY_URL=ws://dev.darkan.org:4040/social/ws
SOCIAL_GATEWAY_TOKEN=darkan-community-world
WORLD_ID=100
```

### Admin announcements API

- `POST /admin/announcements`
- Auth: `Authorization: Bearer <API_KEY>` or `X-API-Key: <API_KEY>`
- Body: JSON `AnnouncementBroadcast` (`type` discriminator required)

See `src/engine/social-gateway.md` for routing behavior and rollout steps.