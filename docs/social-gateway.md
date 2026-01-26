## SocialGateway

### Overview

`SocialGateway` is a central lobby-hosted service that world-servers connect to over a persistent WebSocket. It replaces the legacy `../lobby-server` authenticated REST API flow for social routing.

### Endpoints

- **World WebSocket**: `GET /social/ws`
- **Admin announcements**: `POST /admin/announcements`

### World WebSocket handshake

World connects and sends:

- `WorldHello(worldId, token, worldName)`

Lobby responds:

- `WorldHelloAck(ok=true|false, message?)`

### Core routing behavior

- **Presence**: world sends `PlayerOnline` / `PlayerOffline`; lobby tracks `username -> worldId` in memory.
- **Friends/Ignore/Status**: lobby is authoritative; world sends mutations; lobby updates Mongo `accounts` documents (no schema change) and pushes snapshots/deltas back.
- **PM**:
  - world sends `PmSend(fromUsername, toDisplayName, message)`
  - lobby enforces privacy (`Account.onlineTo`) + ignores + online routing
  - lobby emits `IncomingPm` to recipient and `PmEcho` to sender
- **Friends Chat (FC)**:
  - world sends `FcJoinRequest`, `FcLeave`, `FcKickRequest`, `FcSetRankRequest`, `FcMessage`
  - lobby maintains in-memory membership keyed by FC owner username
  - lobby emits `FcChannelSnapshot` and `FcMessageDeliver`

### Announcements

Admin can POST `AnnouncementBroadcast` to lobby.

- Lobby fans out `AnnouncementBroadcast` to connected worlds.
- Worlds filter delivery by `AnnouncementTarget`:
  - `AllWorlds`: every online player
  - `World(worldId)`: only players on that world
  - `User(username)`: only that player if online
  - `FriendsChat(ownerUsername)`: only players currently in that FC

### Rollout steps

1. **Deploy lobby** with SocialGateway enabled (no client-visible changes until worlds connect).
2. **Deploy world** with SocialClient enabled (connects to lobby, sends PlayerOnline/Offline).
3. Enable/verify:
   - friends list snapshots + deltas
   - ignore list snapshots
   - private status updates
   - PM routing
   - FC join/leave/message/ranks/kick
   - announcements (game + FC target)
4. Once stable, **decommission legacy** `../lobby-server` and remove old API dependencies.


