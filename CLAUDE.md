# CLAUDE.md - Darkan Server Project Guide

This document provides comprehensive guidance for AI assistants working with the Darkan server codebase.

## Project Overview

Darkan is a multiplayer game server emulator implementing **revision 727** of the RuneScape protocol. It consists of a multi-server architecture with a centralized lobby server handling authentication and social features, and multiple world servers handling gameplay.

**Language**: Kotlin 2.3.0
**Build System**: Gradle 9.3
**Runtime**: Java 25
**Database**: MongoDB 5.3.1

---

## Architecture

```
┌─────────────────────────────────────┐
│         Game Client                 │
└──────────────┬──────────────────────┘
               │ TCP Port 43594
               ▼
┌─────────────────────────────────────┐
│         LOBBY SERVER                │
│  ├─ Raw Socket (Port 43594)         │
│  ├─ HTTP API (Port 4040)            │
│  └─ Social Gateway (WebSocket)      │
└──────────────┬──────────────────────┘
               │ ws://lobby:4040/social/ws
               ▼
┌─────────────────────────────────────┐
│      WORLD SERVER(S)                │
│  ├─ Raw Socket (Port 43595)         │
│  └─ SocialClient (WS to Lobby)      │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│          MongoDB                    │
└─────────────────────────────────────┘
```

### Key Architectural Concepts

- **Lobby Server**: Central authority for authentication, account management, world list, and social features (friends, ignores, friends chat)
- **World Server(s)**: Game world instances that connect to the lobby via WebSocket for social feature routing
- **Social Gateway**: WebSocket server hosted by lobby that maintains persistent connections with world servers for real-time presence and messaging
- **JS5 Server**: Cache distribution protocol for syncing game assets to clients

---

## ⚠️ MANDATORY: Specialized Agent Invocation

> **THIS IS A BLOCKING REQUIREMENT. READ THIS SECTION CAREFULLY.**

This project uses **specialized agents** with deep domain expertise for critical subsystems. These agents are the **exclusive code owners** of their respective domains. You **MUST** invoke the appropriate agent for ANY work in their ownership scope.

**FAILURE TO INVOKE THE CORRECT AGENT IS A VIOLATION OF PROJECT GUIDELINES.**

### Why This Matters

1. **Domain Expertise**: Each agent has comprehensive knowledge of RS-specific formats, protocols, and patterns that are non-obvious and require specialized understanding
2. **Code Consistency**: Agents maintain consistent patterns, naming conventions, and architectural decisions within their domains
3. **Correctness**: These systems have complex interdependencies (byte ordering, encryption, compression) where small mistakes cause hard-to-debug failures
4. **Reference Knowledge**: Agents have indexed knowledge of the `../darkan-bot` client codebase for protocol inversion and format reference

### Code Ownership Boundaries

| Agent | Exclusive Ownership | Description |
|-------|---------------------|-------------|
| **cache-library-engineer** | `core/src/main/kotlin/world/gregs/voidps/**` | RS cache format specialists: buffer operations, definition decoders/encoders, compression, cryptography, JS5 protocol |
| **networking-protocol-engineer** | `core/src/main/kotlin/org/darkan/core/net/**` | RS protocol specialists: packet codec, ISAAC cipher, session management, update masks, protocol inversion |
| **rendering-tools-engineer** | `tools/**` | OpenGL/rendering specialists: shaders, LWJGL, ImGui, spatial structures, performance optimization |

### ✅ REQUIRED: When to Invoke Each Agent

#### cache-library-engineer

**INVOKE THIS AGENT FOR:**
- Creating or modifying definition decoders (`ItemDecoder`, `ObjectDecoder`, `NPCDecoder`, `AnimationDecoder`, config decoders, etc.)
- Creating or modifying definition encoders
- ANY changes to `Reader` or `Writer` interfaces or implementations
- ANY changes to `BufferReader`, `BufferWriter`, or `JagExtensions.kt`
- Cache infrastructure (`Cache`, `FileCache`, `MemoryCache`, `ReadOnlyCache`, `Index`)
- Compression code (`DecompressionContext`, GZIP, BZIP2, LZMA)
- Cryptographic utilities (`Xtea`, `CRC`, `Whirlpool`, `Huffman`)
- JS5 file serving (`FileProvider`, `CacheFileProvider`, `MemoryFileProvider`)
- Sector format handling
- Archive/file addressing
- Coordinate and spatial types (`Tile`, `Region`, `Zone`, `Coordinate3D`)
- Definition mixins (`Transforms`, `Recolourable`, `Parameterized`, `Extra`)

**OWNERSHIP PATH**: `core/src/main/kotlin/world/gregs/voidps/buffer/`, `core/src/main/kotlin/world/gregs/voidps/cache/`, `core/src/main/kotlin/world/gregs/voidps/type/`

#### networking-protocol-engineer

**INVOKE THIS AGENT FOR:**
- Adding new `ClientProt` or `ServerProt` packet definitions
- Modifying existing packet definitions
- ANY changes to `Rev727.kt` codec registration
- ANY changes to `Rev727UpdateProtocol.kt` update masks
- Codec DSL modifications (`Codec.kt`)
- Session management (`Session.kt`)
- ISAAC cipher (`Isaac.kt`)
- Packet handler registry (`PacketHandlers.kt`)
- Update mask systems (`MaskRegistry`, `UpdateMaskPresence`, `UpdateMaskKeys`)
- Request/Response opcodes
- Login token handling
- Packet inversion from `../darkan-bot` client code
- Debugging protocol issues or encoding mismatches
- ANY code that reads from or writes to network buffers

**OWNERSHIP PATH**: `core/src/main/kotlin/org/darkan/core/net/`

#### rendering-tools-engineer

**INVOKE THIS AGENT FOR:**
- Creating or modifying GLSL shaders (`.vert`, `.frag` files)
- Mesh rendering code (`Mesh.kt`, `InstancedMesh.kt`)
- Shader program management (`ShaderProgram.kt`, `Shader.kt`)
- Camera system (`Camera.kt`)
- Render system and GL state (`RenderSystem.kt`)
- GPU instancing and batching (`InstanceManager.kt`, `StaticBatcher.kt`)
- Texture atlasing (`TextureAtlas.kt`)
- Uniform buffer objects (`UniformBuffers.kt`)
- Spatial structures (`Octree.kt`, `Quadtree.kt`, `Frustum.kt`, `CullingSystem.kt`, `LODSystem.kt`)
- Model loading from cache (`ModelLoader.kt`, `ModelDefinition.kt`)
- Texture loading and procedural generation (`TextureLoader.kt`, `TextureOperation.kt`, ops/)
- Terrain loading and rendering (`TerrainLoader.kt`, `ChunkedTerrain.kt`)
- ImGui integration (`ImGuiLayer.kt`, `DebugPanel.kt`, `AssetBrowser.kt`)
- Viewer applications (`ModelViewer.kt`, `MapViewer.kt`, `OptimizedMapViewer.kt`, `InterfaceViewer.kt`)
- Window and input handling (`Window.kt`, `InputSystem.kt`, `Keyboard.kt`, `Mouse.kt`)
- Scene graph (`Scene.kt`, `SceneNode.kt`, `SpatialScene.kt`)
- Performance optimization for frame rate, draw calls, memory
- ANY code in the `tools/` submodule

**OWNERSHIP PATH**: `tools/src/main/kotlin/org/darkan/tools/`, `tools/src/main/resources/shaders/`

### ❌ DO NOT

- **DO NOT** attempt to modify owned code directly without invoking the appropriate agent
- **DO NOT** guess at RS-specific formats, byte orderings, or protocol details
- **DO NOT** assume generic solutions work for game-specific subsystems
- **DO NOT** skip agent invocation because a change "seems simple"

### ✅ DO

- **DO** invoke the agent even for small changes in their domain
- **DO** invoke multiple agents if a task crosses ownership boundaries
- **DO** provide the agent with full context about what you're trying to accomplish
- **DO** trust the agent's domain expertise for implementation decisions

### Exception: Explicit User Override

The user may explicitly bypass agent invocation with clear statements such as:
- "Don't use agents for this"
- "Handle this directly without agents"
- "Skip the specialist agent"
- "I want you to do this yourself"

**Without such explicit instruction from the user, agent invocation is MANDATORY.**

### Quick Reference

```
Is the file in world/gregs/voidps/**?     → cache-library-engineer
Is the file in org/darkan/core/net/**?    → networking-protocol-engineer
Is the file in tools/**?                  → rendering-tools-engineer
```

---

## Module Structure

```
server/
├── core/           # Shared library (networking, models, MongoDB, crypto)
├── lobby/          # Lobby server (auth, API, social gateway host)
├── world/          # World server (gameplay, social gateway client)
├── tools/          # Cache/map/interface/CS2 editor suite (LWJGL)
├── migration/      # Legacy → modern database migration (see docs/migration.md)
├── docs/           # Architecture documentation
└── build.gradle.kts
```

### Core Module (`org.darkan.core`)

Shared code used by both lobby and world servers.

| Package | Purpose |
|---------|---------|
| `model` | Domain models: `Account`, `Social`, `FriendsChat`, `Vars` |
| `net` | Networking: `Session`, `RequestOpcode`, `ResponseOpcode` |
| `net.prot` | Protocol implementation: `ClientProt`, `ServerProt`, `Codec` |
| `net.prot.revision` | Revision 727 specifics: `Rev727.kt` |
| `net.prot.handler` | Packet handler registry with reflection-based discovery |
| `net.prot.update` | Entity delta updates: `UpdateMaskPresence`, `MaskRegistry` |
| `net.login` | Stateless login tokens: `LoginToken` (HMAC-SHA256) |
| `net.web` | HTTP/WebSocket utilities: DTOs, exceptions, Discord webhooks |
| `mongo` | MongoDB access: `MongoDB`, `Accounts` collection |
| `mongo.collections` | Collection singletons with index definitions |
| `crypt` | Cryptography: Argon2id password hashing, legacy AES support |
| `worldlist` | World metadata: `WorldList`, `World`, `Country` |
| `social` | Chat models: `ChatMessage`, `QuickChatMessage` |
| `social.gateway` | Gateway protocol: JSON-serialized messages for world↔lobby |
| `engine` | Tick-based game loop: `EngineLoop` (600ms default cycle) |

### Lobby Module (`org.darkan.lobby`)

| Package | Purpose |
|---------|---------|
| `server` | Socket server: `LobbyServer.kt` |
| `server.packet` | Packet handlers for login, social features |
| `server.packet.social` | Friends, ignores, private messages, friends chat handlers |
| `web` | HTTP API: Ktor module setup, routes |
| `web.controller` | REST controllers: accounts, world list |
| `web.model` | Session DTOs: `LobbyPlayer`, `AccountCreationSession` |
| `social` | Social gateway host: `SocialGateway`, `WorldSession` |

### World Module (`org.darkan.world`)

| Package | Purpose |
|---------|---------|
| `server` | Socket server: `WorldServer.kt` |
| `server.packet` | Packet handlers mirroring lobby social handlers |
| `model` | Player state: `WorldPlayer`, `WorldPlayers` |
| `social` | Gateway client: `SocialClient` (WebSocket to lobby) |

---

## Building and Running

### Prerequisites

- Java 23+
- MongoDB instance
- Game cache at `../cache/` (relative to server root)

### Commands

```bash
# Build all modules
./gradlew build

# Run lobby server (blocking)
./gradlew :lobby:run

# Run world server (blocking, requires lobby running)
./gradlew :world:run

# Run tests
./gradlew :core:test

# Build shadow JAR (lobby)
./gradlew :lobby:shadowJar
```

### Startup Flow

**Lobby**:
1. Load protocol definitions (`register727()`)
2. Initialize cache (`Cache.get()`)
3. Start JS5 server for cache distribution
4. Load packet handlers via reflection
5. Start HTTP API on port 4040
6. Start socket server on port 43594
7. Begin engine tick loop

**World**:
1. Load protocol definitions and cache
2. Load packet handlers
3. Connect to lobby's social gateway via WebSocket
4. Start socket server on port 43595

---

## Environment Variables

Create a `.env` file in the project root. Key variables:

### Server Configuration
```env
DEBUG=false
MEMBERS=true
SERVER_NAME=Darkan
```

### Network Ports
```env
LOBBY_PORT=43594
WORLD_PORT=43595
LOBBY_API_PORT=4040
LOCAL_WORLD_ID=0   # >0 lists that world as localhost:WORLD_PORT for local development
```

### Security
```env
JS5_SERVER_TOKEN=<hex>
CLIENT_KEY=<hex>
RSA_JS5_MODULUS=<bigint>
RSA_JS5_EXPONENT=<bigint>
RSA_WORLD_MODULUS=<bigint>
RSA_WORLD_EXPONENT=<bigint>
WORLD_LOGIN_TOKEN_SECRET=<secret>
API_KEY=<admin-api-key>
```

### Cryptography (Argon2id)
```env
CRYPTO_ARGON2_MEMORY=65536
CRYPTO_ARGON2_ITERATIONS=5
CRYPTO_ARGON2_HASH_LENGTH=32
CRYPTO_ARGON2_SALT_LENGTH=16
CRYPTO_ARGON2_PARALLELISM=4
```

### Database
```env
MONGO_URI=mongodb://localhost:27017/darkan-server
MEM_CACHE=false
CACHE_PATH=../cache/
```

### Social Gateway (World Server)
```env
SOCIAL_GATEWAY_URL=ws://localhost:4040/social/ws
SOCIAL_GATEWAY_TOKEN=<shared-secret>
WORLD_ID=300
WORLD_NAME=Darkan
WORLD_HOST=localhost
WORLD_PUBLIC_HOST=localhost
WORLD_MEMBERS=true
WORLD_PVP=false
WORLD_LOOTSHARE=true
```

### Rate Limiting
```env
MULTILOG_WHITELIST=localhost,127.0.0.1,::1
MULTILOG_LIMIT=3
```

### Protocol
```env
MAJOR_VERSION=727
MINOR_VERSION=1
PACKET_SIZE_LIMIT=7500
PACKET_QUEUE_CAPACITY=50
OUTBOUND_QUEUE_CAPACITY=4096
AUTO_UPDATE=false              # dev only: restart onto new CI builds via the ::update countdown
AUTO_UPDATE_POLL_SECONDS=60
AUTO_UPDATE_COUNTDOWN_MINUTES=5
WORLD_CYCLE_NANOS=600000000
```

---

## Database Schema

### MongoDB Collections

**`accounts`** - Player accounts
```kotlin
data class Account(
    val _id: ObjectId,
    val username: String,           // Unique
    val email: String,              // Unique
    val displayName: String?,       // Unique, sparse
    val recoveryEmail: String?,     // Sparse index
    val passwordHash: String,       // Argon2id hash
    val rights: Int,                // Permission level
    val social: Social,             // Friends, ignores, FC settings
    val banEndDate: Long?,
    val muteEndDate: Long?,
    val creationDate: Long,
    val lastLoginDate: Long?
)

data class Social(
    val friends: MutableSet<String>,
    val ignores: MutableSet<String>,
    val friendsChatName: String?,
    val friendsChatRank: FriendsChatRank?,
    val friendsChatKickRank: FriendsChatRank?,
    val privateStatus: PrivateStatus,
    val publicStatus: PublicStatus
)
```

### Indexes
- `email`: Unique
- `displayName`: Unique, sparse
- `recoveryEmail`: Sparse
- `username`: Unique

---

## Networking Protocol

### Request Opcodes (Client → Server)
```kotlin
enum class RequestOpcode {
    CONNECT_LOGIN,      // Login request
    JS5_INIT,           // Cache sync init
    JS5_HIGH_PRIORITY,  // Priority cache request
    JS5_LOW_PRIORITY,   // Background cache request
    ACCOUNT_CREATION    // Create account
}
```

### Response Opcodes (Server → Client)
```kotlin
enum class ResponseOpcode {
    LOGIN_FAILED,
    LOGIN_SUCCESS,
    LOBBY_RESPONSE,
    WORLD_RESPONSE,
    // ... many more
}
```

### Packet Handler Pattern

Handlers are discovered via classpath scanning:

```kotlin
interface PacketHandler<T, K : ClientProt> {
    suspend fun handle(player: T, packet: K)
}

// Example handler
class AddFriendHandler : PacketHandler<LobbyPlayer, AddFriend> {
    override suspend fun handle(player: LobbyPlayer, packet: AddFriend) {
        // Implementation
    }
}
```

Register handlers at startup:
```kotlin
PacketHandlers.load("org.darkan.lobby.server.packet")
```

### Social Gateway Protocol

JSON messages over WebSocket between world servers and lobby:

```kotlin
// World → Lobby
data class WorldHello(val worldId: Int, val token: String)
data class PlayerOnline(val username: String, val displayName: String, val worldId: Int)
data class PlayerOffline(val username: String)
data class PrivateMessage(val from: String, val to: String, val message: ChatMessage)

// Lobby → World
data class UpdateFriend(val player: String, val friend: String, val worldId: Int?)
data class ReceivePrivateMessage(val from: String, val to: String, val message: ChatMessage)
data class Announcement(val message: String, val type: AnnouncementType)
```

---

## Key Patterns and Conventions

### Coding Style

- **Suspend functions**: Use throughout for async operations
- **Coroutines**: `runBlocking` for sync-to-async bridges, `coroutineScope` for structured concurrency
- **Singletons**: Use Kotlin `object` for stateless services (e.g., `Accounts`, `SocialGateway`)
- **Data classes**: For DTOs and models
- **Sealed classes**: For discriminated unions (packet types, enums)

### Naming Conventions

- Packet types: `PascalCase` (e.g., `AddFriend`, `PrivateMessage`)
- Handler classes: `<PacketName>Handler` (e.g., `AddFriendHandler`)
- Collections: Singular name (e.g., `Accounts` object for `accounts` collection)
- Environment variables: `SCREAMING_SNAKE_CASE`

### Error Handling

- Try/catch with logging
- Discord webhook notifications for critical errors
- Graceful shutdown hooks
- `CoroutineExceptionHandler` for async failures

### Session Management

```kotlin
class Session {
    val readChannel: Channel<ClientProt>  // Decoded packet queue

    suspend fun readPackets(channel: ByteReadChannel)
    suspend fun send(packet: ServerProt, noIsaac: Boolean = false)
    fun close()
}
```

### Engine Loop

Tick-based processing (default 600ms per tick):

```kotlin
class EngineLoop(
    val cycleNanos: Long = EnvVars.WORLD_CYCLE_NANOS,
    val warnThresholdMs: Long = EnvVars.WORLD_CYCLE_WARN_MILLIS
) {
    fun start(block: suspend () -> Unit)
}
```

---

## HTTP API Endpoints

### Health Check
```
GET /health
```

### Social Gateway WebSocket
```
WS /social/ws
Header: Authorization: Bearer <SOCIAL_GATEWAY_TOKEN>
```

### Admin Announcements
```
POST /admin/announcements
Header: X-API-Key: <API_KEY>
Body: { "message": "...", "type": "AllWorlds|World|User|FriendsChat", ... }
```

### World List
```
GET /worlds
```

### Account Routes
Various account management endpoints (see `lobby/src/main/kotlin/org/darkan/lobby/web/Routes.kt`)

---

## Testing

Currently minimal test coverage. Run tests with:

```bash
./gradlew :core:test
```

Key test file: `core/src/test/kotlin/org/darkan/core/net/login/WorldLoginTokenTest.kt`

---

## Common Tasks

### Adding a New Packet Handler

1. Define packet class in `core/.../net/prot/ClientProt.kt`:
```kotlin
data class MyPacket(val data: String) : ClientProt
```

2. Create handler in appropriate module:
```kotlin
// lobby/src/main/kotlin/org/darkan/lobby/server/packet/MyPacketHandler.kt
class MyPacketHandler : PacketHandler<LobbyPlayer, MyPacket> {
    override suspend fun handle(player: LobbyPlayer, packet: MyPacket) {
        // Handle packet
    }
}
```

3. Handler is auto-discovered via classpath scanning at startup.

### Adding a New Gateway Message

1. Define message in `core/.../social/gateway/GatewayProtocol.kt`:
```kotlin
@Serializable
data class MyMessage(val data: String) : GatewayPacket
```

2. Handle in `SocialGateway` (lobby) or `SocialClient` (world).

### Adding a New MongoDB Collection

1. Create collection object in `core/.../mongo/collections/`:
```kotlin
object MyCollection {
    val collection by lazy {
        MongoDB.database.getCollection<MyDocument>("my_collection").apply {
            // Create indexes
        }
    }
}
```

2. Define document data class with MongoDB annotations as needed.

### Adding a New API Endpoint

1. Add route in `lobby/.../web/Routes.kt`:
```kotlin
fun Application.configureRoutes() {
    routing {
        get("/my-endpoint") {
            call.respond(MyResponse(...))
        }
    }
}
```

---

## Important Files

| File | Purpose |
|------|---------|
| `core/src/main/kotlin/org/darkan/core/EnvVars.kt` | Environment variable definitions |
| `core/src/main/kotlin/org/darkan/core/net/Session.kt` | Socket session abstraction |
| `core/src/main/kotlin/org/darkan/core/net/prot/ClientProt.kt` | Client packet definitions |
| `core/src/main/kotlin/org/darkan/core/net/prot/ServerProt.kt` | Server packet definitions |
| `core/src/main/kotlin/org/darkan/core/net/prot/handler/PacketHandlers.kt` | Handler registry |
| `core/src/main/kotlin/org/darkan/core/social/gateway/GatewayProtocol.kt` | Gateway message definitions |
| `core/src/main/kotlin/org/darkan/core/mongo/MongoDB.kt` | Database connection |
| `core/src/main/kotlin/org/darkan/core/mongo/collections/Accounts.kt` | Account collection |
| `lobby/src/main/kotlin/org/darkan/lobby/Main.kt` | Lobby entry point |
| `lobby/src/main/kotlin/org/darkan/lobby/social/SocialGateway.kt` | Gateway host |
| `world/src/main/kotlin/org/darkan/world/Main.kt` | World entry point |
| `world/src/main/kotlin/org/darkan/world/social/SocialClient.kt` | Gateway client |

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Kotlin | 2.3.0 | Language |
| Kotlin Coroutines | 1.10.1 | Async/await |
| Ktor | 3.3.3 | HTTP server/client, WebSockets |
| MongoDB Driver | 5.3.1 | Database |
| Kotlinx Serialization | — | JSON serialization |
| Argon2 | 2.12 | Password hashing |
| dotenv-kotlin | 6.5.0 | Environment config |
| LZMA | 1.3 | Compression |
| FastUtil | 8.5.15 | Optimized collections |
| ClassGraph | 4.8.179 | Classpath scanning |

---

## Troubleshooting

### Common Issues

1. **Cache not found**: Ensure `../cache/` directory exists relative to server root
2. **MongoDB connection failed**: Check `MONGO_URI` and MongoDB service status
3. **World can't connect to lobby**: Verify lobby is running and `SOCIAL_GATEWAY_URL` is correct
4. **Token verification failed**: Ensure `SOCIAL_GATEWAY_TOKEN` matches between lobby and world

### Debug Mode

Set `DEBUG=true` in `.env` for verbose logging.

### Discord Notifications

Configure `STAFF_WEBHOOK_URL` for tick performance warnings and error notifications.

---

## Documentation

- `docs/social-gateway.md` - Comprehensive social gateway architecture
- `README.md` - Quick start guide
- `docs/migration.md` - Legacy database migration tooling and runbook
- `docs/deploy.md` - VM layout, systemd units, CI-driven deploys and the legacy cut-over runbook
- `docs/legacy-baseline.md` - Which legacy commits the modern tree is synced to

---

## Notes for AI Assistants

1. **Protocol is revision 727**: Don't assume generic networking; this is game-specific
2. **Suspend functions everywhere**: All I/O operations are coroutine-based
3. **Reflection-based handler discovery**: Handlers are auto-registered via classpath scanning
4. **Lobby is authoritative**: Social features (friends, ignores, chat) are managed by lobby
5. **Multiple world servers**: Architecture supports many worlds connecting to one lobby
6. **Legacy crypto support**: Argon2id is preferred, but AES fallback exists for migration
7. **Cache dependency**: Both servers need access to game cache files
8. **600ms tick rate**: Default game loop timing, configurable via environment
