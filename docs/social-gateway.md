## SocialGateway

### Overview

`SocialGateway` (`lobby/src/main/kotlin/org/darkan/lobby/social/SocialGateway.kt`) is a central
lobby-hosted service that world servers connect to over a persistent WebSocket. It replaces the
legacy `../lobby-server` HTTP/JSON RPC pair (`WorldCommunicator` / `LobbyCommunicator`) with one
live socket per world.

The lobby is authoritative for everything social - friends, ignores, private status, friends chat,
clan chat, private messages. A world never decides any of it; it forwards its players' social
`ClientProt`s and renders the `ServerProt`s the lobby sends back. Clans are the one subsystem that
does not ride on forwarded packets - they have their own typed messages, see [Clans](#clans).

**The lobby is also a place players are.** Somebody sitting on the lobby screen is online, and
their friends have to see that. The gateway reports them on world id `1115`
(`SocialGateway.LOBBY_WORLD_ID`), which is the id the legacy lobby registered itself under
(`lobby-server` `com/rs/lobby/Lobby.java`, `Lobby.WORLD_INFO`). World labels are derived from the
id the way legacy did: `> 1100` renders as `Lobby n`, anything else as `World n`.

### Endpoints

- **World WebSocket**: `GET /social/ws`
- **Admin announcements**: `POST /admin/announcements`

### World WebSocket handshake

1. World → `WorldHello(world: GatewayWorldInfo, token)`.
2. Lobby → `WorldHelloAck(ok, message?)`. The token decides the world's tier: the lobby's private
   `SOCIAL_GATEWAY_TOKEN` makes an official world, and the public `COMMUNITY_WORLD_TOKEN`, accepted
   only while the lobby runs with `DEBUG=true`, a community world (`docs/community-worlds.md`). A bad
   token, an unroutable world (blank host, port `<= 0`), or a community world on an official, local,
   lobby or already-online number is rejected with the reason and the socket closed.
3. World → `WorldPresenceSnapshot(players, reason = CONNECT)`, immediately.

### Presence: the world is the authority, and it says so repeatedly

Presence is pushed, never polled, and it is reconciled rather than merely accumulated. That is what
makes a restart on either side recoverable.

- `PlayerOnline(username, displayName, rightsCrown, privateStatus)` - one player arrived.
- `PlayerOffline(username)` - one player left. Ignored if that player is already recorded somewhere
  else; a player who moved world → lobby has not logged out, and their old world's late goodbye
  must not undo them.
- `WorldPresenceSnapshot(players, reason)` - **everyone** the world currently has in game.
  - `reason = CONNECT`, sent the moment the socket comes up: the lobby rebuilds its presence for
    that world from the list, and re-sends every listed player their whole social state (friend
    list, ignores, chat filter, friends chat, clan channel). A world that outlived a lobby restart
    gets its players' clients repopulated without anybody relogging.
  - `reason = PERIODIC`, sent every `SOCIAL_PRESENCE_RESYNC_SECONDS` (default 120): the lobby only
    applies the difference, so drift - a lost `PlayerOnline`, a socket that died mid-logout -
    cannot outlive one interval.
  - Either way, anyone the lobby still had on that world but who is not in the list goes offline,
    and the resulting `FriendStatus` updates go out to their friends.
- **Socket drop**: the lobby takes every player on that world offline, pushes their friends the
  status change, and removes them from any friends chat or clan chat.
- **Lobby drop**: the world's `SocialClient` reconnects with backoff and re-sends
  `WorldHello` + a `CONNECT` snapshot, which is the recovery path above.

### Friend statuses

`FriendStatus` (one record per friend) carries the friend's display name, world id, and the rank the
*viewer* has given them in the viewer's own channel. World id `0` means offline.

- A friend is online if they are on a world **or** on the lobby screen.
- Visibility is the friend's own private-chat setting, checked at encode time exactly as legacy's
  `FriendStatus.encodePlayer` did: `nobody` always reads offline, `friends` reads offline unless the
  friend has the viewer added back. The update is still sent - it just carries world `0`.
- Ignores play no part in presence. They only gate private-message delivery and friends chat entry.

**Who gets told.** Friendship is one-directional: B can have A on their list without A having B. So
the gateway keeps a reverse index (`friendWatchers`, mirroring legacy `FriendsToNotify`): for each
username, everyone currently online who has them listed. A status change is pushed to that set.
Walking the changed player's *own* friends instead would miss precisely the people who need it.

Statuses are pushed on: lobby login, lobby logout, world login, world logout, world socket drop,
presence reconciliation, a private-status change, and adding or removing a friend.

### Login (lobby or world), in legacy `SocialPlayer.loginSocial` order

Both a lobby login and a `PlayerOnline` run the same `loginSocial`:

1. register the player in the watcher index
2. `FriendlistLoaded`, then `FriendStatus` with the full list
3. `UpdateIgnoreList`
4. `ChatFilterSettingsPrivateChat`
5. push this player's status to everyone watching them
6. re-join the clan chat / guest clan chat they were in
7. re-join the friends chat they were last in

Steps 6 and 7 used to happen only for world logins, which is why a lobby login never restored the
channel the player had joined.

### Friends chat

Membership lives in the lobby, keyed by the owner's username. The remembered channel is
`Account.social.currentFriendsChat`, holding the **owner's display name**:

- set when a player joins a channel,
- cleared when they leave on purpose, when they are kicked, and when the channel is disabled,
- **kept** when they log out - that is the auto-rejoin mechanism,
- `null` means "in no channel".

Join enforces, in order: channel exists and is set up, owner has not ignored the joiner, rank to
enter, 100-member cap, and a one-hour temp ban from a previous kick. Ranks come from the owner's
rank map, with staff (`ADMIN`+) treated as `JMOD` and the owner always `OWNER`. The channel block's
"minimum rank to kick" byte is per-recipient: the owner's own copy carries `0` so the owner can
always kick, as legacy patched it to.

`FcSettingsUpdate` from a world (the FC setup interface lives on the world) applies the new settings
and refreshes the channel, or dissolves it when the name is cleared.

### Private messages

World forwards `PrivateMessage`; the lobby resolves the target by display name across lobby and
world players, enforces mute, `Account.onlineTo`, and the recipient's ignore list, then sends
`MessagePrivate` to the recipient and `MessagePrivateEcho` to the sender.

### Server text to a lobby player

A player on the lobby screen has no game chatbox. The lobby's friends-chat window is the only thing
that renders server text, so chatbox text for a lobby player must be
`GameMessage(ChatMessageType.FC_NOTIFICATION, …)` - a `GAME` message is encoded, sent, and silently
dropped by the client. Use `Session.sendLobbyMessage` / `LobbyPlayer.message`. Legacy drew the same
distinction: `SocialPlayer.sendMessage` used `FC_NOTIFICATION` for lobby players while
`sendGameMessage` (`GAME`) was for players on a world.

### Announcements

`POST /admin/announcements` takes an `AnnouncementBroadcast(channel, message, target)`.

- Lobby-resident players are messaged directly (as `FC_NOTIFICATION`, per above) - they never reach
  a world, so fanning out to the worlds alone would skip them.
- The message is then fanned out to every connected world. `AnnouncementTarget` is meant to select
  the recipients there - `AllWorlds`, `World(worldId)` (`1115` is the lobby), `User(username)`,
  `FriendsChat(ownerUsername)` - but the world's `handleSocialMessage` currently only logs the
  broadcast and shows it to nobody. **Not yet implemented on the world side.**

### Clans

Clans do not use `ClientPacketEnvelope` / `ServerPacketEnvelope` at all. A world turns a button
into a typed `ClanAction` and sends `ClanCommand(username, requestId, action)`; the lobby's
`ClanService` (`lobby/.../social/ClanService.kt`) judges it with the shared `ClanRules`, applies
it, and answers with `ClanCommandResult` plus the events every world replicates from:
`ClanSnapshot` / `ClanDeleted` (the clan document), `ClanChannelUpdate` (a channel's chatter list),
`ClanMembership` (one player's authoritative `Social` clan fields) and `ClanChatMessage` /
`ClanQuickChatMessage` (one line said in a channel). `ClanFetch(clanName)` asks for a snapshot the
world does not have. Each side builds its own client packets from that state with
`org.darkan.core.clan.ClanPackets`; the lobby does so for the players on the lobby screen, whose
clan interfaces (`LobbyClanScreen`) call the same `ClanService.handle` as the gateway does.

The whole protocol - the actions, the rules, the state each side holds and the login/logout/reconnect
behaviour - is specified in [`docs/clans.md`](clans.md).

### Routing packets back to players

`ServerPacketEnvelope(username, packet)` for one player, `ServerPacketBatch(usernames, packet)` for
many. Lobby-resident players are written straight to their own session instead.

### Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SOCIAL_GATEWAY_URL` | `ws://dev.darkan.org:4040/social/ws` | Where a world connects |
| `SOCIAL_GATEWAY_TOKEN` | `COMMUNITY_WORLD_TOKEN` | Private secret of the lobby and its official worlds; a world left on the default joins as a community world |
| `COMMUNITY_WORLD_TOKEN` | `darkan-community-world` | Public token of community worlds, accepted only while the lobby runs with `DEBUG=true` |
| `OFFICIAL_WORLD_MAX` | `99` | Worlds `1`-`OFFICIAL_WORLD_MAX` are refused to community worlds |
| `SOCIAL_PRESENCE_RESYNC_SECONDS` | `120` | How often a world re-announces its online list |

### Testing

`lobby/src/test/kotlin/org/darkan/lobby/harness/` drives the gateway with no Mongo, no socket and no
client: `InMemoryAccounts` is installed as `Accounts.store`, and `LobbyHarness.FakeWorld` builds a
`WorldSession` over a lambda and feeds it `SocialGateway.handleWorldMessage` - the same entry point
the real socket loop uses. `RecordingSession` (the lobby's copy of the world harness's recorder) is
a real `Session` with its writer cancelled, so queued `ServerProt`s can be read back as objects.
`LobbyHarness.reset()` empties the gateway and `ClanService` between tests and installs a fresh
`InMemoryClanStore` as `Clans.store`. See `lobby/src/test/kotlin/org/darkan/lobby/SocialGatewayTest.kt`
and, for the clan half, `ClanServiceTest.kt` (`FakeWorld.command(account, action)` sends a
`ClanCommand`, `messagesOf<T>()` reads back what the lobby pushed).
