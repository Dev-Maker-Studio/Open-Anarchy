package org.darkan.core.net.prot.revision.rev727

import com.rs.cache.loaders.ObjectType
import com.rs.cache.loaders.interfaces.IFEvents
import com.rs.cache.loaders.map.RegionSize as LegacyRegionSize
import com.rs.lib.game.Animation
import com.rs.lib.game.GroundItem
import com.rs.lib.game.HintIcon
import com.rs.lib.game.Item
import com.rs.lib.game.Projectile
import com.rs.lib.game.QuickChatMessage as LegacyQuickChatMessage
import com.rs.lib.game.SpotAnim as LegacySpotAnimModel
import com.rs.lib.game.Tile as LegacyTile
import com.rs.lib.game.WorldObject
import com.rs.lib.io.OutputStream as LegacyStream
import com.rs.lib.model.DisplayNamePair
import com.rs.lib.net.packets.PacketEncoder as LegacyEncoder
import com.rs.lib.net.packets.encoders.BlockMinimapState as LegacyBlockMinimapState
import com.rs.lib.net.packets.encoders.ChatFilterSettings as LegacyChatFilterSettings
import com.rs.lib.net.packets.encoders.ChatFilterSettingsPriv as LegacyChatFilterSettingsPriv
import com.rs.lib.net.packets.encoders.DrawOrder as LegacyDrawOrder
import com.rs.lib.net.packets.encoders.DynamicMapRegion as LegacyDynamicMapRegion
import com.rs.lib.net.packets.encoders.ExecuteCS2 as LegacyExecuteCS2
import com.rs.lib.net.packets.encoders.HintArrow as LegacyHintArrow
import com.rs.lib.net.packets.encoders.MapRegion as LegacyMapRegion
import com.rs.lib.net.packets.encoders.MinimapFlag as LegacyMinimapFlag
import com.rs.lib.net.packets.encoders.NPCUpdate as LegacyNpcUpdate
import com.rs.lib.net.packets.encoders.OpenURL as LegacyOpenUrl
import com.rs.lib.net.packets.encoders.PlayerOption as LegacyPlayerOption
import com.rs.lib.net.packets.encoders.PlayerUpdate as LegacyPlayerUpdate
import com.rs.lib.net.packets.encoders.ReflectionCheckRequest as LegacyReflectionCheckRequest
import com.rs.lib.net.packets.encoders.RunEnergy as LegacyRunEnergy
import com.rs.lib.net.packets.encoders.RunWeight as LegacyRunWeight
import com.rs.lib.net.packets.encoders.SendDevConsoleCommand as LegacySendDevConsoleCommand
import com.rs.lib.net.packets.encoders.SetCursor as LegacySetCursor
import com.rs.lib.net.packets.encoders.Sound as LegacySound
import com.rs.lib.net.packets.encoders.Sound.SoundType as LegacySoundType
import com.rs.lib.net.packets.encoders.SystemUpdateTimer as LegacySystemUpdateTimer
import com.rs.lib.net.packets.encoders.UpdateGESlot as LegacyUpdateGeSlot
import com.rs.lib.net.packets.encoders.UpdateItemContainer as LegacyUpdateItemContainer
import com.rs.lib.net.packets.encoders.UpdateStat as LegacyUpdateStat
import com.rs.lib.net.packets.encoders.WorldLoginDetails as LegacyWorldLoginDetails
import com.rs.lib.net.packets.encoders.accountcreation.CheckEmailResponse as LegacyCheckEmailResponse
import com.rs.lib.net.packets.encoders.accountcreation.CreateAccountResponse as LegacyCreateAccountResponse
import com.rs.lib.net.packets.encoders.camera.CamForceAngle as LegacyCamForceAngle
import com.rs.lib.net.packets.encoders.camera.CamLookAt as LegacyCamLookAt
import com.rs.lib.net.packets.encoders.camera.CamMoveTo as LegacyCamMoveTo
import com.rs.lib.net.packets.encoders.camera.CamRemoveRoof as LegacyCamRemoveRoof
import com.rs.lib.net.packets.encoders.camera.CamShake as LegacyCamShake
import com.rs.lib.net.packets.encoders.interfaces.IFCloseSub as LegacyIfCloseSub
import com.rs.lib.net.packets.encoders.interfaces.IFOpenTop as LegacyIfOpenTop
import com.rs.lib.net.packets.encoders.interfaces.IFSetAngle as LegacyIfSetAngle
import com.rs.lib.net.packets.encoders.interfaces.IFSetAnimation as LegacyIfSetAnimation
import com.rs.lib.net.packets.encoders.interfaces.IFSetClickMask as LegacyIfSetClickMask
import com.rs.lib.net.packets.encoders.interfaces.IFSetColor as LegacyIfSetColor
import com.rs.lib.net.packets.encoders.interfaces.IFSetEvents as LegacyIfSetEvents
import com.rs.lib.net.packets.encoders.interfaces.IFSetGraphic as LegacyIfSetGraphic
import com.rs.lib.net.packets.encoders.interfaces.IFSetHide as LegacyIfSetHide
import com.rs.lib.net.packets.encoders.interfaces.IFSetItem as LegacyIfSetItem
import com.rs.lib.net.packets.encoders.interfaces.IFSetModel as LegacyIfSetModel
import com.rs.lib.net.packets.encoders.interfaces.IFSetNPCHead as LegacyIfSetNpcHead
import com.rs.lib.net.packets.encoders.interfaces.IFSetPlayerHead as LegacyIfSetPlayerHead
import com.rs.lib.net.packets.encoders.interfaces.IFSetPlayerHeadIgnoreWorn as LegacyIfSetPlayerHeadIgnoreWorn
import com.rs.lib.net.packets.encoders.interfaces.IFSetPlayerHeadOther as LegacyIfSetPlayerHeadOther
import com.rs.lib.net.packets.encoders.interfaces.IFSetPlayerModel as LegacyIfSetPlayerModel
import com.rs.lib.net.packets.encoders.interfaces.IFSetPlayerModelOther as LegacyIfSetPlayerModelOther
import com.rs.lib.net.packets.encoders.interfaces.IFSetPosition as LegacyIfSetPosition
import com.rs.lib.net.packets.encoders.interfaces.IFSetScrollPos as LegacyIfSetScrollPos
import com.rs.lib.net.packets.encoders.interfaces.IFSetTargetParam as LegacyIfSetTargetParam
import com.rs.lib.net.packets.encoders.interfaces.IFSetText as LegacyIfSetText
import com.rs.lib.net.packets.encoders.interfaces.IFSetTextFont as LegacyIfSetTextFont
import com.rs.lib.net.packets.encoders.interfaces.opensub.IFOpenSub as LegacyIfOpenSub
import com.rs.lib.net.packets.encoders.interfaces.opensub.IFOpenSubActiveGroundItem as LegacyIfOpenSubActiveGroundItem
import com.rs.lib.net.packets.encoders.interfaces.opensub.IFOpenSubActiveNPC as LegacyIfOpenSubActiveNpc
import com.rs.lib.net.packets.encoders.interfaces.opensub.IFOpenSubActiveObject as LegacyIfOpenSubActiveObject
import com.rs.lib.net.packets.encoders.interfaces.opensub.IFOpenSubActivePlayer as LegacyIfOpenSubActivePlayer
import com.rs.lib.net.packets.encoders.social.IgnoreList as LegacyIgnoreList
import com.rs.lib.net.packets.encoders.social.MessageGame as LegacyMessageGame
import com.rs.lib.net.packets.encoders.social.QuickChatPrivate as LegacyQuickChatPrivate
import com.rs.lib.net.packets.encoders.updatezone.AddObject as LegacyAddObject
import com.rs.lib.net.packets.encoders.updatezone.CreateGroundItem as LegacyCreateGroundItem
import com.rs.lib.net.packets.encoders.updatezone.ObjectAnim as LegacyObjectAnim
import com.rs.lib.net.packets.encoders.updatezone.ObjectPrefetch as LegacyObjectPrefetch
import com.rs.lib.net.packets.encoders.updatezone.ProjAnim as LegacyProjAnim
import com.rs.lib.net.packets.encoders.updatezone.ProjAnimHalfSq as LegacyProjAnimHalfSq
import com.rs.lib.net.packets.encoders.updatezone.RemoveGroundItem as LegacyRemoveGroundItem
import com.rs.lib.net.packets.encoders.updatezone.RemoveObject as LegacyRemoveObject
import com.rs.lib.net.packets.encoders.updatezone.RevealGroundItem as LegacyRevealGroundItem
import com.rs.lib.net.packets.encoders.updatezone.SetGroundItemAmount as LegacySetGroundItemAmount
import com.rs.lib.net.packets.encoders.updatezone.TileMessage as LegacyTileMessage
import com.rs.lib.net.packets.encoders.updatezone.TileSound as LegacyTileSound
import com.rs.lib.net.packets.encoders.updatezone.TileSpotAnim as LegacyTileSpotAnim
import com.rs.lib.net.packets.encoders.updatezone.UpdateZoneFull as LegacyUpdateZoneFull
import com.rs.lib.net.packets.encoders.updatezone.UpdateZonePartial as LegacyUpdateZonePartial
import com.rs.lib.net.packets.encoders.updatezone.UpdateZonePartialEnclosed as LegacyUpdateZonePartialEnclosed
import com.rs.lib.net.packets.encoders.vars.SetVarc as LegacySetVarc
import com.rs.lib.net.packets.encoders.vars.SetVarcString as LegacySetVarcString
import com.rs.lib.net.packets.encoders.vars.SetVarp as LegacySetVarp
import com.rs.lib.net.packets.encoders.vars.SetVarpBit as LegacySetVarpBit
import com.rs.lib.net.packets.encoders.zonespecific.ObjectAnimSpecific as LegacyObjectAnimSpecific
import com.rs.lib.net.packets.encoders.zonespecific.ProjAnimSpecific as LegacyProjAnimSpecific
import com.rs.lib.net.packets.encoders.zonespecific.SpotAnimSpecific as LegacySpotAnimSpecific
import com.rs.lib.util.Utils
import com.rs.lib.util.reflect.ReflectionCheck as LegacyReflectionCheck
import com.rs.lib.util.reflect.ReflectionChecks as LegacyReflectionChecks
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import org.darkan.core.clientwatch.ReflectionCheck
import org.darkan.core.clientwatch.ReflectionCheckType
import org.darkan.core.model.ChatMessageType
import org.darkan.core.model.RegionSize
import org.darkan.core.net.prot.Codec
import org.darkan.core.net.prot.ServerProt
import org.darkan.core.net.prot.ZonePacketEncoder
import org.darkan.core.net.prot.dto.CheckEmailReplyOpcode
import org.darkan.core.net.prot.dto.CreateAccountReplyOpcode
import org.darkan.core.net.prot.dto.GrandExchangeOffer
import org.darkan.core.net.prot.dto.IgnoreEntry
import org.darkan.core.net.prot.dto.InvSlotUpdate
import org.darkan.core.net.prot.packet.*
import org.darkan.core.social.QuickChatMessage
import world.gregs.voidps.type.Tile
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class Rev727LegacyEncoderDifferentialTest {

    private lateinit var codec: Codec

    @BeforeTest
    fun setUp() {
        codec = register727()
    }

    private class Case(val name: String, val legacy: LegacyEncoder, val modern: ServerProt)

    private class CaseBuilder {
        val cases = mutableListOf<Case>()
        fun case(name: String, legacy: LegacyEncoder, modern: ServerProt) {
            cases.add(Case(name, legacy, modern))
        }
    }

    @Test
    fun `every covered encoder produces the legacy bytes`() {
        val cases = cases()
        val failures = mutableListOf<String>()
        for (case in cases) {
            val legacyBytes = try {
                encodeLegacy(case.legacy)
            } catch (e: Throwable) {
                failures.add("${case.name}: legacy encoder threw ${e::class.simpleName}: ${e.message}")
                continue
            }
            val modernBytes = try {
                encodeModern(case.modern)
            } catch (e: Throwable) {
                failures.add("${case.name}: modern encoder threw ${e::class.simpleName}: ${e.message}")
                continue
            }
            if (legacyBytes.isEmpty()) {
                failures.add("${case.name}: legacy encoder produced an empty payload")
                continue
            }
            if (!legacyBytes.contentEquals(modernBytes)) {
                failures.add(describeMismatch(case, legacyBytes, modernBytes))
            }
        }
        assertTrue(
            failures.isEmpty(),
            "${failures.size} of ${cases.size} encoders disagree with the legacy library:\n\n" +
                failures.joinToString("\n\n")
        )
    }

    @Test
    fun `the harness detects a difference when there is one`() {
        val legacy = encodeLegacy(LegacyRunEnergy(97))
        val modern = encodeModern(RunEnergy(98))
        assertTrue(legacy.isNotEmpty() && modern.isNotEmpty(), "both sides must produce bytes")
        assertTrue(!legacy.contentEquals(modern), "a one-byte difference must not compare equal")
    }

    @Test
    fun `the coverage bookkeeping is honest`() {
        val covered = cases().map { it.modern::class.simpleName }.toSet()
        val overlap = covered.intersect(UNCOVERED.keys)
        assertTrue(overlap.isEmpty(), "Listed as uncovered but actually covered: $overlap")
        assertTrue(
            cases().size >= MINIMUM_CASE_COUNT,
            "Expected at least $MINIMUM_CASE_COUNT byte-equality cases, found ${cases().size}"
        )
    }


    private fun encodeLegacy(encoder: LegacyEncoder): ByteArray {
        val stream = LegacyStream(BUFFER_CAPACITY)
        encoder.encodeBody(stream)
        return stream.toByteArray()
    }

    private fun encodeModern(prot: ServerProt): ByteArray = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        codec.encodeServerProt(prot, channel)
        channel.flush()
        val bytes = ByteArray(channel.availableForRead)
        if (bytes.isNotEmpty()) channel.readFully(bytes)
        channel.close()
        bytes
    }

    private fun describeMismatch(case: Case, legacy: ByteArray, modern: ByteArray): String {
        val opcode = codec.serverProts[case.modern::class]?.opcode ?: -1
        val firstDiff = (0 until maxOf(legacy.size, modern.size))
            .firstOrNull { legacy.getOrNull(it) != modern.getOrNull(it) } ?: -1
        return buildString {
            append("${case.name} (opcode $opcode, ${case.modern::class.simpleName})")
            append("\n  first difference at byte $firstDiff")
            append("\n  legacy (${legacy.size} bytes): ${hex(legacy)}")
            append("\n  modern (${modern.size} bytes): ${hex(modern)}")
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02x".format(it) }


    private fun chunkId(x: Int, y: Int, plane: Int) = (x shl 11) or y or (plane shl 22)

    private fun chunkLocalHash(localX: Int, localY: Int) = ((localX and 0x7) shl 4) or (localY and 0x7)

    private fun legacyTile(x: Int, y: Int, plane: Int = 0): LegacyTile = LegacyTile.of(x, y, plane)

    private fun tile(x: Int, y: Int, plane: Int = 0) = Tile(x, y, plane)

    private fun legacyObject(id: Int, type: ObjectType, rotation: Int, x: Int, y: Int, plane: Int = 0) =
        WorldObject(id, type, rotation, legacyTile(x, y, plane))

    private fun legacyGroundItem(id: Int, amount: Int, x: Int, y: Int, plane: Int = 0) =
        GroundItem(Item(id, amount), legacyTile(x, y, plane))

    private fun zeroXteas() = intArrayOf(0, 0, 0, 0)

    private fun legacyChunkBits(chunks: IntArray): ByteArray {
        val stream = LegacyStream(BUFFER_CAPACITY)
        stream.initBitAccess()
        for (chunk in chunks) {
            if (chunk == BuildInstancedRegion.ABSENT_CHUNK) {
                stream.writeBits(1, 0)
            } else {
                stream.writeBits(1, 1)
                stream.writeBits(26, chunk)
            }
        }
        stream.finishBitAccess()
        return stream.toByteArray()
    }

    private fun cases(): List<Case> = CaseBuilder().apply {
        accountCases()
        cameraCases()
        interfaceCases()
        regionCases()
        soundCases()
        varCases()
        zoneCases()
        miscCases()
        socialCases()
    }.cases


    private fun CaseBuilder.accountCases() {
        case(
            "CREATE_CHECK_EMAIL_REPLY",
            LegacyCheckEmailResponse(CheckEmailReplyOpcode.EMAIL_ALREADY_IN_USE.value),
            CreateCheckEmailReply(CheckEmailReplyOpcode.EMAIL_ALREADY_IN_USE),
        )
        case(
            "CREATE_ACCOUNT_REPLY",
            LegacyCreateAccountResponse(CreateAccountReplyOpcode.SERVER_ERROR.value),
            CreateAccountReply(CreateAccountReplyOpcode.SERVER_ERROR),
        )
        case(
            "WORLD_LOGIN_DETAILS",
            LegacyWorldLoginDetails(2, 1337, "Zezima", 3, true, true),
            WorldLoginDetails(
                rights = 2,
                modLevel = 3,
                quickChat = false,
                verifiedEmail = true,
                aBool7322 = true,
                quickChatOnly = false,
                playerIndex = 1337,
                members = true,
                dob = 0,
                memberWorld = true,
                worldName = "Zezima",
            ),
        )
        case("UPDATE_REBOOT_TIMER", LegacySystemUpdateTimer(50), UpdateRebootTimer(50))
        case("RUN_ENERGY", LegacyRunEnergy(97), RunEnergy(97))
        case("PLAYER_WEIGHT", LegacyRunWeight(-40), PlayerWeight(-40))
        case("UPDATE_STAT", LegacyUpdateStat(3, 13_034_431, 99), UpdateStat(xp = 13_034_431, skillId = 3, level = 99))
        case(
            "UPDATE_GE_SLOT",
            LegacyUpdateGeSlot(2, 1, 4151, 250_000, 10, 4, 1_000_000),
            UpdateGESlot(
                slot = 2,
                offer = GrandExchangeOffer(
                    progress = 1, itemId = 4151, price = 250_000,
                    amount = 10, currentAmount = 4, totalPrice = 1_000_000,
                ),
            ),
        )
    }


    private fun CaseBuilder.cameraCases() {
        case("CAM_FORCEANGLE", LegacyCamForceAngle(40_000, 50_000), CamForceAngle(40_000, 50_000))
        case("CAM_LOOKAT", LegacyCamLookAt(30, 45, 3000, 7, 9), CamLookAt(30, 45, 3000, 7, 9))
        case("CAM_MOVETO", LegacyCamMoveTo(30, 45, 3000, 7, 9), CamMoveTo(30, 45, 3000, 7, 9))
        case("CAM_REMOVEROOF", LegacyCamRemoveRoof(3222, 3218), CamRemoveRoof(tile(3222, 3218)))
        case("CAM_SHAKE", LegacyCamShake(1, 2, 3, 4, 5000), CamShake(slotId = 1, v1 = 2, v2 = 3, v3 = 4, v4 = 5000))
    }


    private fun CaseBuilder.interfaceCases() {
        case("IF_SETPLAYERHEAD", LegacyIfSetPlayerHead(750, 31), IfSetPlayerHead(750, 31))
        case(
            "IF_SETPLAYERHEAD_IGNOREWORN",
            LegacyIfSetPlayerHeadIgnoreWorn(750, 31, 100, 200, 300),
            IfSetPlayerHeadIgnoreWorn(750, 31, identiKit1 = 100, identiKit2 = 300, identiKit3 = 200),
        )
        case("IF_SETTEXTFONT", LegacyIfSetTextFont(750, 31, 495), IfSetTextFont(750, 31, 495))
        case("IF_OPENTOP", LegacyIfOpenTop(548, 2), IfOpenTop(548, 2, zeroXteas()))
        case("IF_SETHIDE", LegacyIfSetHide(750, 31, true), IfSetHide(750, 31, true))
        case("IF_SETGRAPHIC", LegacyIfSetGraphic(750, 31, 0x0BADF00D), IfSetGraphic(750, 31, 0x0BADF00D))
        case("IF_OPENSUB", LegacyIfOpenSub(548, 23, 750, true), IfOpenSub(548, 23, 750, true, zeroXteas()))
        case("IF_SETANGLE", LegacyIfSetAngle(750, 31, 100, 200, 40_000), IfSetAngle(750, 31, 100, 200, 40_000))
        case("IF_SETPOSITION", LegacyIfSetPosition(750, 31, 40, 50), IfSetPosition(750, 31, 40, 50))
        case(
            "IF_SETPLAYERMODEL_OTHER",
            LegacyIfSetPlayerModelOther(750, 31, "Zezima", 1337),
            IfSetPlayerModelOther(750, 31, primaryModelId = 1337, secondaryModelId = Utils.stringToInt("Zezima")),
        )
        case("IF_SETSCROLLPOS", LegacyIfSetScrollPos(750, 31, 512), IfSetScrollPos(750, 31, 512))
        case("IF_SETPLAYERMODEL", LegacyIfSetPlayerModel(750, 31), IfSetPlayerModel(750, 31))
        case("IF_CLOSESUB", LegacyIfCloseSub(750, 31), IfCloseSub(750, 31))
        case("IF_SETANIM", LegacyIfSetAnimation(750, 31, 0x0BADF00D), IfSetAnim(750, 31, 0x0BADF00D))
        case("IF_SETCOLOR", LegacyIfSetColor(750, 31, 40_000), IfSetColor(750, 31, 40_000))
        case("IF_SETNPCHEAD", LegacyIfSetNpcHead(750, 31, 1234), IfSetNpcHead(750, 31, 1234))
        case("IF_SETITEM", LegacyIfSetItem(750, 31, 4151, 1_000_000), IfSetItem(750, 31, 4151, 1_000_000))
        case(
            "IF_SETEVENTS (opcode 121, client enum `IF_SETTARGETPARAM`)",
            LegacyIfSetEvents(IFEvents(750, 31, 0, 27, 0x7654321)),
            IfSetEvents(750, 31, fromSlot = 0, toSlot = 27, settings = 0x7654321),
        )
        case("IF_SETTEXT", LegacyIfSetText(750, 31, "Hello, world!"), IfSetText(750, 31, "Hello, world!"))
        case("IF_SETMODEL", LegacyIfSetModel(750, 31, 0x0BADF00D), IfSetModel(750, 31, 0x0BADF00D))
        case(
            "IF_SETTARGETPARAM (opcode 148, client enum `IF_RESETTARGETPARAM`)",
            LegacyIfSetTargetParam(750, 31, 0, 27, 1000),
            IfSetTargetParam(750, 31, targetInterfaceId = 1000, fromSlot = 0, toSlot = 27),
        )
        case("IF_SETCLICKMASK", LegacyIfSetClickMask(750, 31, true), IfSetClickMask(750, 31, true))
        case(
            "IF_SETPLAYERHEAD_OTHER",
            LegacyIfSetPlayerHeadOther(750, 31, "Zezima", 1337),
            IfSetPlayerHeadOther(750, 31, key = Utils.stringToInt("Zezima"), value = 1337),
        )
        case(
            "IF_OPENSUB_ACTIVE_OBJECT",
            LegacyIfOpenSubActiveObject(548, 23, 750, true, legacyObject(1276, ObjectType.SCENERY_INTERACT, 2, 3222, 3218)),
            IfOpenSubActiveObject(
                interfaceId = 548,
                componentId = 23,
                subInterfaceId = 750,
                overlay = true,
                xteas = zeroXteas(),
                objectId = 1276,
                tile = tile(3222, 3218),
                type = ObjectType.SCENERY_INTERACT.id,
                rotation = 2,
            ),
        )
        case(
            "IF_OPENSUB_ACTIVE_NPC",
            LegacyIfOpenSubActiveNpc(548, 23, 750, true, 4321),
            IfOpenSubActiveNpc(548, 23, 750, true, npcIndex = 4321, xteas = zeroXteas()),
        )
        case(
            "IF_OPENSUB_ACTIVE_PLAYER",
            LegacyIfOpenSubActivePlayer(548, 23, 750, true, 1337),
            IfOpenSubActivePlayer(548, 23, 750, true, playerIndex = 1337, xteas = zeroXteas()),
        )
        case(
            "IF_OPENSUB_ACTIVE_GROUNDITEM",
            LegacyIfOpenSubActiveGroundItem(548, 23, 750, true, legacyGroundItem(4151, 1, 3222, 3218)),
            IfOpenSubActiveGroundItem(
                interfaceId = 548,
                componentId = 23,
                subInterfaceId = 750,
                overlay = true,
                tile = tile(3222, 3218),
                itemId = 4151,
                xteas = zeroXteas(),
            ),
        )
    }


    private fun CaseBuilder.regionCases() {
        val regionIds = BuildRegion.regionIds(400, 402, RegionSize.SIZE_104)
        case(
            "REGION",
            LegacyMapRegion(null, LegacyRegionSize.SIZE_104, 400, 402, true, regionIds.toSortedSet()),
            BuildRegion(
                regionIds = regionIds,
                mapSize = RegionSize.SIZE_104,
                xteas = Array(regionIds.size) { zeroXteas() },
                chunkX = 400,
                chunkY = 402,
                forceMapRefresh = true,
            ),
        )
        val instancedChunks = intArrayOf(
            BuildInstancedRegion.chunkDescriptor(416, 392, 0, 0),
            BuildInstancedRegion.ABSENT_CHUNK,
            BuildInstancedRegion.chunkDescriptor(400, 400, 1, 3),
            BuildInstancedRegion.chunkDescriptor(400, 424, 2, 1),
            BuildInstancedRegion.chunkDescriptor(401, 401, 3, 2),
        )
        val instancedRegionIds = BuildInstancedRegion.regionIds(instancedChunks)
        case(
            "DYNAMIC_MAP_REGION",
            LegacyDynamicMapRegion(null, LegacyRegionSize.SIZE_104, 400, 402, true, legacyChunkBits(instancedChunks), instancedRegionIds),
            BuildInstancedRegion(
                mapSize = RegionSize.SIZE_104,
                chunks = instancedChunks,
                xteas = Array(instancedRegionIds.size) { zeroXteas() },
                chunkX = 400,
                chunkY = 402,
                forceMapRefresh = true,
            ),
        )
        case("MINIMAP_FLAG", LegacyMinimapFlag(40, 55), MinimapFlag(40, 55))
        case(
            "HINT_ARROW (cleared)",
            LegacyHintArrow(HintIcon().apply { targetType = 0; index = 3 }),
            HintArrow(iconIndex = 3, targetType = 0),
        )
        case(
            "HINT_ARROW (player)",
            LegacyHintArrow(
                HintIcon().apply {
                    targetType = 1; targetIndex = 1337; arrowType = 2; modelId = 0x0BADF00D; index = 3
                },
            ),
            HintArrow(
                iconIndex = 3, targetType = 1, arrowGraphic = 2,
                targetIndex = 1337, denominator = 2500, modelId = 0x0BADF00D,
            ),
        )
        case(
            "HINT_ARROW (npc)",
            LegacyHintArrow(
                HintIcon().apply {
                    targetType = 10; targetIndex = 4321; arrowType = 2; modelId = 0x0BADF00D; index = 3
                },
            ),
            HintArrow(
                iconIndex = 3, targetType = 10, arrowGraphic = 2,
                targetIndex = 4321, denominator = 2500, modelId = 0x0BADF00D,
            ),
        )
        case(
            "HINT_ARROW (tile)",
            LegacyHintArrow(
                HintIcon().apply {
                    targetType = 2; arrowType = 2; coordX = 3222; coordY = 3218
                    plane = 0; distanceFromFloor = 32; modelId = 0x0BADF00D; index = 3
                },
            ),
            HintArrow(
                iconIndex = 3, targetType = 2, arrowGraphic = 2, plane = 0,
                x = 3222, y = 3218, height = 32 * 4, distance = -1, modelId = 0x0BADF00D,
            ),
        )
    }


    private fun CaseBuilder.soundCases() {
        case(
            "SOUND_SYNTH",
            LegacySound(2677, 40, 200, 8192, LegacySoundType.EFFECT).repeat(3),
            SoundSynth(soundId = 2677, repeat = 3, delay = 40, volume = 200, sampleRate = 8192),
        )
        case(
            "VORBIS_SOUND",
            LegacySound(2677, 40, 200, 8192, LegacySoundType.VORB_EFFECT).repeat(3),
            VorbisSound(soundId = 2677, repeat = 3, delay = 40, volume = 200, sampleRate = 8192),
        )
        case(
            "VORBIS_SPEECH_SOUND",
            LegacySound(2677, 40, 200, 8192, LegacySoundType.VOICE).repeat(3),
            VorbisSpeechSound(soundId = 2677, repeat = 3, delay = 40, volume = 200),
        )
        case(
            "MUSIC_EFFECT",
            LegacySound(1234, 40, 200, 8192, LegacySoundType.JINGLE),
            MusicEffect(unused = 0, volume = 200, musicId = 1234),
        )
        case(
            "MUSIC_TRACK",
            LegacySound(1234, 40, 200, 8192, LegacySoundType.MUSIC),
            MusicTrack(delay = 40, volume = 200, musicId = 1234),
        )
    }


    private fun CaseBuilder.varCases() {
        case("VARP_SMALL", LegacySetVarp(1234, 100), VarpSmall(1234, 100))
        case("VARP_LARGE", LegacySetVarp(1234, 0x0BADF00D), VarpLarge(1234, 0x0BADF00D))
        case("CLIENT_SETVARC_SMALL", LegacySetVarc(1234, 100), ClientSetVarcSmall(1234, 100))
        case("CLIENT_SETVARC_LARGE", LegacySetVarc(1234, 0x0BADF00D), ClientSetVarcLarge(1234, 0x0BADF00D))
        case("VARBIT_SMALL", LegacySetVarpBit(1234, 100), VarbitSmall(1234, 100))
        case("VARBIT_LARGE", LegacySetVarpBit(1234, 0x0BADF00D), VarbitLarge(1234, 0x0BADF00D))
        case("CLIENT_SETVARCSTR_SMALL", LegacySetVarcString(1234, "short"), ClientSetVarcStrSmall(1234, "short"))
        case("CLIENT_SETVARCSTR_LARGE", LegacySetVarcString(1234, LONG_STRING), ClientSetVarcStrLarge(1234, LONG_STRING))
    }


    private fun CaseBuilder.zoneCases() {
        val hash = chunkLocalHash(5, 6)
        case(
            "UPDATE_ZONE_FULL_FOLLOWS",
            LegacyUpdateZoneFull(chunkId(400, 402, 0), chunkId(404, 405, 1)),
            UpdateZoneFullFollows(chunkX = 4, chunkY = 3, plane = 1),
        )
        case(
            "UPDATE_ZONE_PARTIAL_FOLLOWS",
            LegacyUpdateZonePartial(chunkId(400, 402, 0), chunkId(404, 405, 1)),
            UpdateZonePartialFollows(chunkX = 4, chunkY = 3, plane = 1),
        )
        case(
            "UPDATE_ZONE_PARTIAL_ENCLOSED",
            LegacyUpdateZonePartialEnclosed(
                chunkId(400, 402, 0),
                chunkId(404, 405, 1),
                LegacyRemoveGroundItem(hash, legacyGroundItem(4151, 1, 3222, 3218)),
                LegacyAddObject(hash, legacyObject(1276, ObjectType.SCENERY_INTERACT, 2, 3222, 3218)),
            ),
            UpdateZonePartialEnclosed(
                chunkX = 4,
                chunkY = 3,
                plane = 1,
                data = ZonePacketEncoder.encodeZoneSubPackets(
                    listOf(
                        RemoveGroundItem(localX = 5, localY = 6, itemId = 4151),
                        CreateObject(
                            localX = 5, localY = 6, objectId = 1276,
                            shape = ObjectType.SCENERY_INTERACT.id, rotation = 2,
                        ),
                    ),
                ),
            ),
        )
        case(
            "CREATE_OBJECT",
            LegacyAddObject(hash, legacyObject(1276, ObjectType.SCENERY_INTERACT, 2, 3222, 3218)),
            CreateObject(localX = 5, localY = 6, objectId = 1276, shape = ObjectType.SCENERY_INTERACT.id, rotation = 2),
        )
        case(
            "DESTROY_OBJECT",
            LegacyRemoveObject(hash, legacyObject(1276, ObjectType.SCENERY_INTERACT, 2, 3222, 3218)),
            DestroyObject(localX = 5, localY = 6, shape = ObjectType.SCENERY_INTERACT.id, rotation = 2),
        )
        case(
            "OBJ_ANIM",
            LegacyObjectAnim(hash, legacyObject(1276, ObjectType.SCENERY_INTERACT, 2, 3222, 3218), Animation(0x0BADF00D)),
            ObjAnim(localX = 5, localY = 6, shape = ObjectType.SCENERY_INTERACT.id, rotation = 2, animationId = 0x0BADF00D),
        )
        case(
            "OBJ_ANIM_SPECIFIC",
            LegacyObjectAnimSpecific(legacyObject(1276, ObjectType.SCENERY_INTERACT, 2, 3222, 3218), Animation(0x0BADF00D)),
            ObjAnimSpecific(tile = tile(3222, 3218), shape = ObjectType.SCENERY_INTERACT.id, rotation = 2, animationId = 0x0BADF00D),
        )
        case(
            "OBJECT_PREFETCH",
            LegacyObjectPrefetch(1276, ObjectType.SCENERY_INTERACT),
            ObjectPrefetch(objectId = 1276, typeIndex = ObjectType.SCENERY_INTERACT.id),
        )
        case(
            "CREATE_GROUND_ITEM",
            LegacyCreateGroundItem(hash, legacyGroundItem(4151, 40_000, 3222, 3218)),
            CreateGroundItem(localX = 5, localY = 6, itemId = 4151, amount = 40_000),
        )
        case(
            "REMOVE_GROUND_ITEM",
            LegacyRemoveGroundItem(hash, legacyGroundItem(4151, 1, 3222, 3218)),
            RemoveGroundItem(localX = 5, localY = 6, itemId = 4151),
        )
        case(
            "GROUND_ITEM_REVEAL",
            LegacyRevealGroundItem(hash, legacyGroundItem(4151, 40_000, 3222, 3218), 1337),
            GroundItemReveal(playerId = 1337, localX = 5, localY = 6, itemId = 4151, amount = 40_000),
        )
        case(
            "GROUND_ITEM_COUNT",
            LegacySetGroundItemAmount(hash, legacyGroundItem(4151, 40_000, 3222, 3218), 20_000),
            GroundItemCount(localX = 5, localY = 6, itemId = 4151, oldAmount = 20_000, newAmount = 40_000),
        )
        case(
            "TILE_MESSAGE",
            LegacyTileMessage(hash, "Hello, world!", 900, 40, 0xFF0000),
            TileMessage(localX = 5, localY = 6, priority = 900 / 30, duration = 40, color = 0xFF0000, message = "Hello, world!"),
        )
        case(
            "SPOT_ANIM",
            LegacyTileSpotAnim(hash, LegacySpotAnimModel(1234, 30, 100, 2)),
            SpotAnim(localX = 5, localY = 6, spotAnimId = 1234, height = 100, speed = 30, rotation = 2),
        )
        case(
            "SPOT_ANIM_SPECIFIC",
            LegacySpotAnimSpecific(LegacySpotAnimModel(1234, 30, 100, 2), tile(3222, 3218).id),
            SpotAnimSpecific(
                slot = 0,
                spotAnimId = 1234,
                settings = LegacySpotAnimModel(1234, 30, 100, 2).settings2Hash,
                speed = 30,
                targetHash = tile(3222, 3218).id,
                height = 100,
            ),
        )
        case(
            "MIDI_SONG_LOCATION (zone sound effect)",
            LegacyTileSound(hash, LegacySound(2677, 40, 200, 8192, LegacySoundType.EFFECT).radius(3).repeat(2)),
            SoundEffectTile(
                localX = 5, localY = 6, soundId = 2677, range = 3, mode = 2,
                delay = 40, volume = 200, sampleRate = 8192,
            ),
        )
        case(
            "SOUND_AREA (zone vorbis sound)",
            LegacyUpdateZonePartialEnclosed(
                chunkId(400, 402, 0),
                chunkId(404, 405, 1),
                LegacyTileSound(hash, LegacySound(2677, 40, 200, 8192, LegacySoundType.VORB_EFFECT).radius(3).repeat(2)),
                LegacyTileSound(hash, LegacySound(2677, 40, 200, 8192, LegacySoundType.VOICE).radius(3).repeat(2)),
            ),
            UpdateZonePartialEnclosed(
                chunkX = 4,
                chunkY = 3,
                plane = 1,
                data = ZonePacketEncoder.encodeZoneSubPackets(
                    listOf(
                        SoundAreaTile(
                            localX = 5, localY = 6, soundId = 2677, range = 3, mode = 2,
                            delay = 40, volume = 200, sampleRate = 8192, voice = false,
                        ),
                        SoundAreaTile(
                            localX = 5, localY = 6, soundId = 2677, range = 3, mode = 2,
                            delay = 40, volume = 200, sampleRate = 8192, voice = true,
                        ),
                    ),
                ),
            ),
        )
        case(
            "MAP_PROJANIM",
            LegacyProjAnim(hash, projectile()),
            MapProjAnim(
                localX = 6, localY = 2,
                xOffset = 4, yOffset = 3,
                lockOn = 4321, spotAnimId = 1234,
                startHeight = WIRE_START_HEIGHT * 4, endHeight = WIRE_END_HEIGHT * 4,
                startTime = 30, endTime = 30 + 60,
                angle = 15, slope = 12,
                useFloorHeight = false,
            ),
        )
        case(
            "MAP_PROJANIM_HALFSQ",
            LegacyProjAnimHalfSq(hash, projectile()),
            MapProjAnimHalfSq(
                localX = 12, localY = 4,
                xOffset = 8, yOffset = 6,
                source = 1337, lockOn = 4321, spotAnimId = 1234,
                startHeight = WIRE_START_HEIGHT * 4, endHeight = WIRE_END_HEIGHT * 4,
                startTime = 30, endTime = 30 + 60,
                angle = 15, slope = 12,
                basOffIdx = -1, useFloorHeight = false,
            ),
        )
        case(
            "PROJANIM_SPECIFIC",
            LegacyProjAnimSpecific(projectile()),
            ProjAnimSpecific(
                endHeight = WIRE_END_HEIGHT * 4, source = 1337, endTime = 60, lockOn = 4321,
                flags = 0, y = 3218, slope = 12, startTime = 30, spotAnimId = 1234,
                angle = 15, xDist = 4, yDist = 3, x = 3222, startHeight = WIRE_START_HEIGHT * 4,
            ),
        )
    }

    private fun projectile() = Projectile(
        legacyTile(3222, 3218), 1337, legacyTile(3226, 3221),
        4321, 1234, WIRE_START_HEIGHT, WIRE_END_HEIGHT, 30, 60, 12, 15,
    )


    private fun CaseBuilder.miscCases() {
        case("PROCESS_DEV_CONSOLE_COMMAND", LegacySendDevConsoleCommand("::reload"), ProcessDevConsoleCommand("::reload"))
        case("OPEN_URL", LegacyOpenUrl("https://darkan.org"), OpenUrl("https://darkan.org"))
        case("SET_CURSOR", LegacySetCursor("Attack", 42), SetCursor(cursorId = 42, uptext = "Attack"))
        case("SET_DRAW_ORDER", LegacyDrawOrder(true), SetDrawOrder(true))
        case("BLOCK_MINIMAP_STATE", LegacyBlockMinimapState(2), BlockMinimapState(2))
        case("CHAT_FILTER_SETTINGS", LegacyChatFilterSettings(1, 2), ChatFilterSettings(tradeFilter = 1, publicFilter = 2))
        case("CHAT_FILTER_SETTINGS_PRIVATECHAT", LegacyChatFilterSettingsPriv(1), ChatFilterSettingsPrivateChat(1))
        case(
            "PLAYER_OPTION",
            LegacyPlayerOption("Attack", 3, true, 40_000),
            PlayerOption("Attack", slot = 3, top = true, cursor = 40_000),
        )
        case("PLAYER_UPDATE", LegacyPlayerUpdate(BLOB), PlayerUpdate(BLOB))
        case("NPC_UPDATE", LegacyNpcUpdate(false, BLOB), NpcUpdate(BLOB))
        case("NPC_UPDATE_LARGE", LegacyNpcUpdate(true, BLOB), NpcUpdateLarge(BLOB))
        case(
            "RUN_CS2_SCRIPT",
            LegacyExecuteCS2(1234, "text", 42, "more"),
            RunScript(1234, arrayOf("text", 42, "more")),
        )
        val legacyChecks = LegacyReflectionChecks()
            .setReflectionChecks(arrayOf(LegacyReflectionCheck("java.lang.System", "out")))
        case(
            "REFLECTION_CHECK",
            LegacyReflectionCheckRequest(legacyChecks),
            ReflectionRequest(
                id = legacyChecks.id,
                checks = arrayOf(ReflectionCheck(ReflectionCheckType.GET_FIELD_MODIFIERS, "java.lang.System", "out")),
            ),
        )
        case(
            "UPDATE_INV_FULL",
            LegacyUpdateItemContainer(93, false, arrayOf(Item(4151, 1), Item(995, 1_000_000), null)),
            UpdateInvFull(
                containerId = 93,
                negativeKey = false,
                itemIds = intArrayOf(4151, 995, -1),
                amounts = intArrayOf(1, 1_000_000, 0),
            ),
        )
        case(
            "UPDATE_INV_PARTIAL",
            LegacyUpdateItemContainer(93, false, arrayOf(Item(4151, 1), Item(995, 1_000_000), null), 0, 1, 2),
            UpdateInvPartial(
                containerId = 93,
                negativeKey = false,
                updates = arrayOf(
                    InvSlotUpdate(0, 4151, 1),
                    InvSlotUpdate(1, 995, 1_000_000),
                    InvSlotUpdate(2, -1, 0),
                ),
            ),
        )
    }


    private fun CaseBuilder.socialCases() {
        case(
            "UPDATE_IGNORE_LIST",
            LegacyIgnoreList(linkedMapOf("zezima" to DisplayNamePair("Zezima", "Zezima_old"))),
            UpdateIgnoreList(arrayOf(IgnoreEntry(username = "Zezima", displayName = "Zezima_old"))),
        )
        case(
            "GAME_MESSAGE",
            LegacyMessageGame(LegacyMessageGame.MessageType.GAME, "Hello, world!"),
            GameMessage(type = ChatMessageType.GAME, message = "Hello, world!"),
        )
        case(
            "MESSAGE_QUICKCHAT_PRIVATE_ECHO",
            LegacyQuickChatPrivate("Zezima", legacyQuickChat()),
            MessageQuickChatPrivateEcho(
                senderDisplayName = "Zezima",
                message = QuickChatMessage(message = "", fileId = 1234, data = QC_DATA),
            ),
        )
    }

    private fun legacyQuickChat() = LegacyQuickChatMessage(1234, QC_DATA)

    private companion object {
        const val BUFFER_CAPACITY = 8192

        const val WIRE_START_HEIGHT = 10
        const val WIRE_END_HEIGHT = 5

        val LONG_STRING = "x".repeat(200)

        val BLOB = byteArrayOf(0x00, 0x7f, -0x80, -0x01, 0x2a)

        val QC_DATA = byteArrayOf(0x01, 0x02, 0x03)

        const val MINIMUM_CASE_COUNT = 95

        val UNCOVERED: Map<String, String> = mapOf(
            "MessagePublic" to
                "Legacy compresses the body with the cache Huffman table; the modern encoder " +
                "stamps a live tick counter (`writeHashedMessageTimestamp`). Neither side is " +
                "reproducible without freezing the clock and loading the cache.",
            "MessageFriendsChat" to
                "Legacy pads the header with five `Utils.getRandomInclusive(255)` junk bytes and " +
                "Huffman-compresses the body; modern stamps a live timestamp.",
            "MessageClanChannel" to "Legacy random junk bytes + Huffman; modern live timestamp.",
            "MessagePrivate" to "Legacy random junk bytes + Huffman; modern live timestamp.",
            "MessagePrivateEcho" to "Legacy Huffman-compresses the body, so it needs an initialised cache.",
            "MessageQuickChatFriendsChat" to "Legacy random junk bytes; modern live timestamp.",
            "MessageQuickChatClanChannel" to "Legacy random junk bytes; modern live timestamp.",
            "MessageQuickChatPrivate" to
                "Modern stamps a live tick counter into the five bytes where legacy writes zeros; " +
                "the value is not derivable from the packet's own fields.",

            "Cutscene" to
                "Legacy resolves `CutsceneDefinitions.getDefs(id)` and `MapXTEAs` to build the " +
                "region key block. The modern packet takes the keys as data, so there is no " +
                "shared seam without loading the cache.",
            "CustomizeObject" to
                "Legacy reads `ObjectDefinitions` to decide how many model/colour/texture entries " +
                "to write; the modern packet writes the arrays it is handed.",
            "VarclanSetString" to "Legacy `SetVarClan` picks its opcode from `ClanVarDefinitions`, which needs the cache.",
            "VarclanSetLong" to "Legacy `SetVarClan` picks its opcode from `ClanVarDefinitions`, which needs the cache.",
            "VarclanSetInt" to "Legacy `SetVarClan` picks its opcode from `ClanVarDefinitions`, which needs the cache.",
            "VarclanSetByte" to "Legacy `SetVarClan` picks its opcode from `ClanVarDefinitions`, which needs the cache.",

            "ClanChannelFull" to
                "Legacy takes the channel block pre-encoded, built by a static helper that returns " +
                "`OutputStream.getBuffer()` - the whole doubling-grown backing array rather than " +
                "`toByteArray()` - so its output carries trailing zero padding and is not a " +
                "well-formed payload to diff against.",
            "ClanSettingsFull" to "Same `getBuffer()` padding defect in the legacy static block builder.",
            "ClanChannelDelta" to "Legacy has no delta encoder; the lobby built these blocks by hand.",
            "ClanSettingsDelta" to "Legacy has no delta encoder.",
            "ClanSettingsFullRaw" to "Raw passthrough of an externally built block; nothing to differ on.",
            "FriendsChatChannel" to
                "Legacy takes the whole channel block pre-encoded from the lobby server; the " +
                "modern packet builds it from typed fields. No shared seam inside core.",
            "UpdateFriendchatChannelSingleUser" to "No legacy encoder; the lobby wrote this block by hand.",
            "FriendStatus" to
                "Legacy encodes from a live `Account`/`Friend`/`WorldInfo` object graph and reads " +
                "friends-chat rank off the account's social state; the modern packet carries flat " +
                "DTOs. Constructing an equivalent legacy graph would test the account model, not " +
                "the encoder.",
            "HintTrail" to
                "Legacy walks a route-finder step array to build the trail; the modern packet " +
                "carries it pre-encoded, so only the shared 3-byte header would be comparable.",
            "AddIgnore" to
                "Legacy encodes from a live `Account`, hard-codes its flag byte to 0x2 and writes " +
                "display name then previous display name where the modern packet writes username " +
                "then display name. No shared seam.",

            "IfSetReTex" to "No legacy encoder (`IF_SETRETEX`, opcode 132).",
            "IfSetReCol" to "No legacy encoder (`IF_SETRECOL`, opcode 144).",
            "IfMoveSub" to "No legacy encoder (`IF_MOVESUB`, opcode 13).",
            "IfSetTextAntiMacro" to "No legacy encoder (`IF_SETTEXTANTIMACRO`, opcode 36).",
            "OutdatedSetTheoraString" to "No legacy encoder (opcode 20).",
            "OutdatedInterfacePulseEvent" to "No legacy encoder (opcode 96).",
            "AnimateNpc" to "No legacy encoder (`ANIMATE_NPC`, opcode 145).",
            "MusicTrackTile" to "No legacy encoder (`PLAY_SONG_AT_POSITION`, opcode 93).",
            "UpdateInvStopTransmit" to "No legacy encoder (`UPDATE_INV_STOP_TRANSMIT`, opcode 42).",
            "UpdateUid192" to "No legacy encoder (`UPDATE_UID192`, opcode 90).",
            "UpdateDob" to "No legacy encoder (`UPDATE_DOB`, opcode 157).",
            "SetTarget" to "No legacy encoder (`SET_TARGET`, opcode 129).",
            "ReduceAttackPriority" to "No legacy encoder (`REDUCE_ATTACK_PRIORITY`, opcode 61).",
            "ShowFaceHere" to "No legacy encoder (`SHOW_FACE_HERE`, opcode 21).",
            "DebugServerTriggers" to "No legacy encoder (`DEBUG_SERVER_TRIGGERS`, opcode 73).",
            "QuickHopWorlds" to "No legacy encoder (`QUICK_HOP_WORLDS`, opcode 58).",
            "IdentifyHostName" to "No legacy encoder (`IDENTIFY_HOST_NAME`, opcode 147).",
            "RequestFps" to "No legacy encoder (`REQUEST_FPS`, opcode 159).",
            "UpdateSitesettingsCookie" to "No legacy encoder (`UPDATE_SITESETTINGS_COOKIE`, opcode 91).",
            "OpenSocialNetworkUrl" to "No legacy encoder (`OPEN_SOCIAL_NETWORK_URL`, opcode 86).",
            "WindowCallWithArgs" to "No legacy encoder (`WINDOW_CALL_WITH_ARGS`, opcode 156).",
            "DisplayNameChangeState" to "No legacy encoder (opcode 52).",
            "LoyaltyUpdate" to "No legacy encoder (`LOYALTY_UPDATE`, opcode 32).",
            "JCoinsUpdate" to "No legacy encoder (`JCOINS_UPDATE`, opcode 75).",
            "ApplyDebug" to "No legacy encoder (`APPLY_DEBUG`, opcode 76).",
            "StopVorbisStream" to "No legacy encoder (opcode 11).",
            "VorbisPreloadSound" to "No legacy encoder (opcode 17).",
            "SetVorbisSound" to "No legacy encoder (opcode 106).",
            "PreloadSong" to "No legacy encoder (`PRELOAD_SONG`, opcode 77).",
            "SoundMixbussSetLevel" to "No legacy encoder (`SET_SONG_VOLUME`, opcode 134).",
            "MessageQuickChatPlayerGroup" to "No legacy encoder (opcode 83).",
            "MessagePlayerGroup" to "No legacy encoder (opcode 133).",
            "WorldListPacket" to "No legacy encoder in `rs.darkan:core`; the lobby server built the list itself.",
            "LobbyLoginDetails" to "No legacy encoder in `rs.darkan:core`; the lobby server wrote this blob inline.",

            "LogoutLobby" to "Zero-length payload; nothing to compare.",
            "LogoutFull" to "Zero-length payload; nothing to compare.",
            "CamResetHard" to "Zero-length payload; nothing to compare.",
            "CamResetSmooth" to "Zero-length payload; nothing to compare.",
            "ClearVarps" to "Zero-length payload; nothing to compare.",
            "VarclanEnable" to "Zero-length payload; nothing to compare.",
            "VarclanDisable" to "Zero-length payload; nothing to compare.",
            "ResetSounds" to "Zero-length payload; nothing to compare.",
            "ResetAllAnimations" to "Zero-length payload; nothing to compare.",
            "TriggerOnDialogAbort" to "Zero-length payload; nothing to compare.",
            "KeepAlive" to "Zero-length payload; nothing to compare.",
            "Pong" to "Zero-length payload; nothing to compare.",
            "FriendlistLoaded" to "Zero-length payload; nothing to compare.",
        )

        @Suppress("unused")
        val KNOWN_DIVERGENCES: Map<String, String> = mapOf(
            "IF_OPENTOP" to
                "Legacy writes the last two xtea words with `writeIntLittle`; the client reads " +
                "them with a plain big-endian `readInt()` (`PacketDecoder.IF_OPENTOP`), which is " +
                "what the modern encoder does. Legacy hard-codes all four words to zero, so its " +
                "bug was never observable.",
            "Projectile heights" to
                "The client multiplies the start- and end-height bytes by 4 on the way in " +
                "(`startHeight = readUnsignedByte() * 4`), except that MAP_PROJANIM_HALFSQ and " +
                "PROJANIM_SPECIFIC reinterpret start height as a signed byte when the 0x2 " +
                "BAS-offset flag is set. The modern encoders implement exactly that; the legacy " +
                "`Projectile` simply stores the raw wire byte, so its callers pre-divide.",
            "MESSAGE_QUICKCHAT_PRIVATE / _ECHO" to
                "The legacy `ServerPacket` enum maps opcode 22 to MESSAGE_QUICKCHAT_PRIVATE and " +
                "31 to MESSAGE_QUICKCHAT_PRIVATE_ECHO. The client has it the other way round: " +
                "opcode 22 is name + file id with no crown and no ignore check (an echo), opcode " +
                "31 carries the name block, five hash bytes, a crown byte and the ignore check. " +
                "The modern codec follows the client.",
            "MAP_PROJANIM_HALFSQ size" to
                "The client's own tables contradict each other - `ServerProt` says 17, " +
                "`UpdateZonePacket` says 19, and the decoder consumes 19. Both stacks send 19 " +
                "bytes; only the standalone framing length differs, which is why this packet must " +
                "be sent enclosed.",
        )
    }
}
