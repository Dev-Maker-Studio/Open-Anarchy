# Community worlds

Anyone can run their own world against the dev lobby (`dev.darkan.org`) and have it appear in the
dev lobby's world list. It is a dev-lobby feature only: the lobby accepts community worlds while it
runs with `DEBUG=true`, and the production lobby never does.

## Running one

1. Clone the server repository with its `cache-source` submodule. The world has to serve the same
   cache as the dev lobby, so stay on the same `dev` commit it runs; with no prepacked cache at
   `CACHE_PATH` the world builds and serves the submodule by itself (about a minute the first time).
2. Run a MongoDB on `localhost:27017`, e.g. `docker run -d -p 27017:27017 mongo:7`. The world keeps
   its own accounts and player saves there; it never touches the dev database.
3. Create `.env` in the repository root:

   ```env
   WORLD_ID=123                  # 100-999, and not the lobby's local world (300)
   WORLD_NAME=My World
   WORLD_PUBLIC_HOST=203.0.113.7 # the address players' clients connect to
   WORLD_PORT=43595              # open it to the internet
   ```

4. `./gradlew :world:run`.

Everything else defaults to the dev lobby: `SOCIAL_GATEWAY_URL=ws://dev.darkan.org:4040/social/ws`
and `SOCIAL_GATEWAY_TOKEN=darkan-community-world`, the public community token. A world whose
gateway token is that public one is a community world.

## What the lobby enforces

- A community world may not use an official number (`1`-`OFFICIAL_WORLD_MAX`, 99 by default), the
  lobby's `LOCAL_WORLD_ID`, a lobby number (1000+), or a number that is already online. The
  handshake is refused with the reason, and the world logs it.
- A community world never holds the login-token secret. For each login it sends the player's token
  to the lobby (`VerifyLoginToken`); the lobby checks it, spends it (a token works once), and
  answers with the account's username and display name (`LoginTokenVerdict`). The world creates a
  local account from that answer on the player's first login.
- The lobby only believes a community world about players it admitted there: presence,
  friends/ignores, private and friends chat, friends chat settings and clan commands for anyone
  else are dropped, and the display name and crown always come from the lobby's own account, not
  from the world. Community worlds cannot send announcements.
- Official worlds use the private `SOCIAL_GATEWAY_TOKEN` and `WORLD_LOGIN_TOKEN_SECRET` and none of
  these rules apply to them.

## Limits

- A player's login token lasts five minutes and is not tied to a world: the lobby issues it at lobby
  login, before the player picks a world, and the RSA login block has no room for more. A modified
  community world can therefore replay the token of a player who joins it on another world inside
  that window. Only join community worlds you trust; staff accounts should not join them at all.
- The lobby forgets a community world's admissions when its gateway socket drops, so its players
  lose friends and chat until they relog.
