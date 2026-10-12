package com.rs.net.update

import com.rs.Settings
import com.rs.game.World
import com.rs.game.model.entity.Entity
import com.rs.game.model.entity.Hit
import com.rs.game.model.entity.HitBar
import com.rs.game.model.entity.npc.NPC
import com.rs.game.model.entity.player.Player
import com.rs.utils.crawls
import org.darkan.core.Utils
import org.darkan.core.model.MoveType
import org.darkan.core.net.prot.update.NpcUpdateNpc
import org.darkan.core.net.prot.update.NpcUpdateSnapshot
import org.darkan.core.net.prot.update.PlayerUpdatePlayer
import org.darkan.core.net.prot.update.PlayerUpdateSnapshot
import org.darkan.core.update.CommonUpdateBlocks
import org.darkan.core.update.FaceTile
import org.darkan.core.update.ForceMovement
import org.darkan.core.update.HitBarUpdate
import org.darkan.core.update.HitSplat
import org.darkan.core.update.HitsUpdate
import org.darkan.core.update.MovementAnimations
import org.darkan.core.update.NpcCustomisation
import org.darkan.core.update.NpcUpdateBlocks
import org.darkan.core.update.PlayerAppearance
import org.darkan.core.update.PlayerUpdateBlocks
import org.darkan.core.update.Tint
import org.darkan.core.update.SpotAnim as CoreSpotAnim
import org.darkan.core.model.SpotAnim as LegacySpotAnim

object EntitySnapshotFactory {

    fun buildNpcSnapshot(observer: Player): NpcUpdateSnapshot {
        val largeScene = observer.hasLargeSceneView()
        val radius = if (largeScene) 126 else 14

        val npcs = observer.queryNearbyNPCsByTileRange(radius) { npc ->
            npc.withinDistance(observer, radius)
        }

        val npcSnapshots = ArrayList<NpcUpdateNpc>(minOf(npcs.size, Settings.LOCAL_NPCS_LIMIT))
        for (n in npcs) {
            if (npcSnapshots.size >= Settings.LOCAL_NPCS_LIMIT) break
            npcSnapshots.add(buildNpcUpdateNpc(n, observer, added = false))
        }

        return NpcUpdateSnapshot(
            largeSceneView = largeScene,
            localTileHash30 = observer.tileHash,
            nearbyNpcs = npcSnapshots,
        )
    }

    fun buildNpcUpdateNpc(n: NPC, observer: Player, added: Boolean): NpcUpdateNpc {
        return NpcUpdateNpc(
            index = n.index,
            id = n.id,
            plane = n.plane,
            x = n.x,
            y = n.y,
            size = n.size,
            finished = n.hasFinished(),
            hasTeleported = n.hasTeleported(),
            nextWalkDirection = n.nextWalkDirection?.id,
            nextRunDirection = n.nextRunDirection?.id,
            movementType = when {
                n.nextRunDirection != null -> MoveType.RUN
                n.definitions.crawls -> MoveType.CRAWL
                else -> MoveType.WALK
            },
            faceAngle = n.faceAngle,
            blocks = buildNpcBlocks(n, observer, added),
        )
    }

    fun buildNpcBlocks(n: NPC, observer: Player, added: Boolean): NpcUpdateBlocks {
        val spotAnims = arrayOfNulls<CoreSpotAnim>(4)
        spotAnims[0] = convertSpotAnim(n.nextSpotAnim1)
        spotAnims[1] = convertSpotAnim(n.nextSpotAnim2)
        spotAnims[2] = convertSpotAnim(n.nextSpotAnim3)
        spotAnims[3] = convertSpotAnim(n.nextSpotAnim4)

        val faceEntity: Int? = if (n.nextFaceEntity != -2) n.nextFaceEntity else null
        val persistentFaceEntity: Int? = if (n.lastFaceEntity != -1) n.lastFaceEntity else null

        val faceTile: FaceTile? = when {
            n.fixedFaceTile != null -> {
                val tile = n.fixedFaceTile
                FaceTile(tile.x, tile.y)
            }
            n.nextFaceTile != null && n.nextRunDirection == null && n.nextWalkDirection == null -> {
                val tile = n.nextFaceTile
                FaceTile(tile.x, tile.y)
            }
            else -> null
        }

        val tint: Tint? = n.nextBodyGlow?.let { glow ->
            Tint(
                hue = glow.redAdd,
                saturation = glow.greenAdd,
                lightness = glow.blueAdd,
                opacity = glow.scalar,
                startCycles = 0,
                endCycles = glow.time,
            )
        }

        val hits: HitsUpdate? = resolveNpcHits(observer, n)

        val modelOverrides = n.bodyModelRotator?.toModelOverrides()

        val common = CommonUpdateBlocks(
            tint = tint,
            spotAnims = spotAnims,
            forceMovement = n.nextForceMovement?.let { fm ->
                ForceMovement(
                    diffX1 = fm.diffX1,
                    diffY1 = fm.diffY1,
                    diffX2 = fm.diffX2,
                    diffY2 = fm.diffY2,
                    startClientCycles = fm.startClientCycles,
                    speedClientCycles = fm.speedClientCycles,
                    direction = fm.direction,
                )
            },
            faceEntity = faceEntity,
            persistentFaceEntity = persistentFaceEntity,
            faceTile = faceTile,
            hits = hits,
            modelOverrides = modelOverrides,
        )

        val movementAnimations: MovementAnimations? = n.nextAnimation?.let { anim ->
            MovementAnimations(
                ids = anim.ids,
                speed = anim.speed,
            )
        }

        val customisation: NpcCustomisation? = n.bodyMeshModifier?.toCustomisation()

        val basId: Int? = if (n.bas != -1) {
            if (n.bas == -2) -1 else n.bas
        } else null

        val transformNpcId: Int? = n.nextTransformation?.toNPCId()

        val combatLevel: Int? = if (n.hasChangedCombatLevel()) n.combatLevel else null
        val persistentCombatLevel: Int? = if (n.customCombatLevel >= 0) n.combatLevel else null

        val name: String? = if (n.hasChangedName()) n.name else null
        val persistentName: String? = if (n.customName != null) n.name else null

        val say: String? = n.nextForceTalk?.text()

        return NpcUpdateBlocks(
            common = common,
            movementAnimations = movementAnimations,
            customisation = customisation,
            basId = basId,
            transformNpcId = transformNpcId,
            combatLevel = combatLevel,
            persistentCombatLevel = persistentCombatLevel,
            name = name,
            persistentName = persistentName,
            say = say,
        )
    }

    fun resolveNpcHits(observer: Player, n: NPC): HitsUpdate? {
        if (n.nextHits.isEmpty() && n.nextHitBars.isEmpty()) return null

        val splats = Array<HitSplat>(n.nextHits.size) { i ->
            val hit = n.nextHits[i]
            resolveHitSplat(hit, observer, n)
        }

        val bars = Array<HitBarUpdate>(n.nextHitBars.size) { i ->
            val bar = n.nextHitBars[i]
            resolveHitBar(bar, observer)
        }

        return HitsUpdate(
            hitSplats = splats,
            hitBars = bars,
        )
    }

    private fun resolveHitSplat(hit: Hit, observer: Player, target: Entity): HitSplat {
        val interactingWith = hit.interactingWith(observer, target)

        if (hit.text == null && hit.missed() && !interactingWith) {
            return HitSplat.Miss(
                missValue = 0,
                delay = hit.delay,
            )
        }

        val soaking = hit.soaking

        if (soaking != null) {
            val hasText = hit.text != null
            val bigDamage = hit.damage > Short.MAX_VALUE / 2
            val bigSoakDamage = soaking.damage > Short.MAX_VALUE / 2

            if (hasText || bigDamage || bigSoakDamage) {
                return HitSplat.CustomText(
                    id = hit.getMark(observer, target),
                    text = hit.text ?: Utils.formatNumber(hit.damage),
                    soakId = soaking.getMark(observer, target),
                    soakText = Utils.formatNumber(soaking.damage),
                    delay = hit.delay,
                )
            } else {
                return HitSplat.Soak(
                    id = hit.getMark(observer, target),
                    value = hit.damage,
                    soakId = soaking.getMark(observer, target),
                    soakValue = soaking.damage,
                    delay = hit.delay,
                )
            }
        }

        val hasText = hit.text != null
        val bigDamage = hit.damage > Short.MAX_VALUE / 2

        if (hasText || bigDamage) {
            return HitSplat.CustomText(
                id = hit.getMark(observer, target),
                text = hit.text ?: Utils.formatNumber(hit.damage),
                delay = hit.delay,
            )
        }

        return HitSplat.Standard(
            id = hit.getMark(observer, target),
            value = hit.damage,
            delay = hit.delay,
        )
    }

    private fun resolveHitBar(bar: HitBar, observer: Player): HitBarUpdate {
        val perc = bar.percentage
        val toPerc = bar.toPercentage
        val display = bar.display(observer)

        if (!display) {
            return HitBarUpdate(
                type = bar.type,
                timer = 32767,
            )
        }

        val timer = if (perc != toPerc) bar.timer else 0

        return HitBarUpdate(
            type = bar.type,
            timer = timer,
            delay = bar.delay,
            fromPercentage = perc,
            toPercentage = toPerc,
        )
    }


    fun buildPlayerSnapshot(observer: Player): PlayerUpdateSnapshot {
        val playersByIndex = arrayOfNulls<PlayerUpdatePlayer>(2048)
        for (index in 1 until 2048) {
            val p = World.players[index] ?: continue
            if (p.hasStarted() && !p.hasFinished()) {
                playersByIndex[index] = buildPlayerUpdatePlayer(p, observer)
            }
        }
        return PlayerUpdateSnapshot(
            localIndex = observer.index,
            largeSceneView = observer.hasLargeSceneView(),
            localTileHash30 = observer.tileHash,
            playersByIndex = playersByIndex,
        )
    }

    fun buildPlayerUpdatePlayer(p: Player, observer: Player): PlayerUpdatePlayer {
        var walkOpcode: Int? = null
        var runOpcode: Int? = null
        var zeroDisplacementWalk = false

        val walkDir = p.nextWalkDirection
        if (walkDir != null) {
            val runDir = p.nextRunDirection
            if (runDir != null) {
                val totalDx = walkDir.dx + runDir.dx
                val totalDy = walkDir.dy + runDir.dy

                if (totalDx == 0 && totalDy == 0) {
                    walkOpcode = null
                    runOpcode = null
                    zeroDisplacementWalk = true
                } else {
                    val runOp = Utils.getPlayerRunningDirection(totalDx, totalDy)
                    if (runOp != -1) {
                        walkOpcode = Utils.getPlayerWalkingDirection(walkDir.dx, walkDir.dy)
                        runOpcode = runOp
                    } else {
                        walkOpcode = Utils.getPlayerWalkingDirection(totalDx, totalDy)
                        runOpcode = null
                    }
                }
            } else {
                val dx = walkDir.dx
                val dy = walkDir.dy
                if (dx == 0 && dy == 0) {
                    walkOpcode = null
                    runOpcode = null
                    zeroDisplacementWalk = true
                } else {
                    walkOpcode = Utils.getPlayerWalkingDirection(dx, dy)
                    runOpcode = null
                }
            }
        }

        return PlayerUpdatePlayer(
            index = p.index,
            tileHash30 = p.tileHash,
            lastTileHash30 = p.lastTile?.getTileHash() ?: p.tileHash,
            regionHash18 = p.regionHash,
            xInRegion = p.xInRegion,
            yInRegion = p.yInRegion,
            finished = p.hasFinished(),
            hidden = p.isTrulyHidden,
            hasTeleported = p.hasTeleported(),
            nextWalkDirection = walkOpcode,
            nextRunDirectionOrOpcode = runOpcode,
            blocks = buildPlayerBlocks(p, observer, added = false),
            zeroDisplacementWalk = zeroDisplacementWalk,
        )
    }

    fun buildPlayerBlocks(p: Player, observer: Player, added: Boolean): PlayerUpdateBlocks {
        val spotAnims = arrayOfNulls<CoreSpotAnim>(4)
        spotAnims[0] = convertSpotAnim(p.nextSpotAnim1)
        spotAnims[1] = convertSpotAnim(p.nextSpotAnim2)
        spotAnims[2] = convertSpotAnim(p.nextSpotAnim3)
        spotAnims[3] = convertSpotAnim(p.nextSpotAnim4)

        val faceEntity: Int? = if (p.nextFaceEntity != -2) p.nextFaceEntity else null
        val persistentFaceEntity: Int? = if (p.lastFaceEntity != -1) p.lastFaceEntity else null

        val faceDirection: Int? = when {
            p.fixedFaceTile != null -> p.faceAngle
            p.nextFaceTile != null && p.nextRunDirection == null && p.nextWalkDirection == null -> p.faceAngle
            else -> null
        }
        val persistentFaceDirection: Int? = p.faceAngle

        val tint: Tint? = p.nextBodyGlow?.let { glow ->
            Tint(
                hue = glow.redAdd,
                saturation = glow.greenAdd,
                lightness = glow.blueAdd,
                opacity = glow.scalar,
                startCycles = 0,
                endCycles = glow.time,
            )
        }

        val hits: HitsUpdate? = resolvePlayerHits(observer, p)

        val modelOverrides = p.bodyModelRotator?.toModelOverrides()

        val common = CommonUpdateBlocks(
            tint = tint,
            spotAnims = spotAnims,
            forceMovement = p.nextForceMovement?.let { fm ->
                ForceMovement(
                    diffX1 = fm.diffX1,
                    diffY1 = fm.diffY1,
                    diffX2 = fm.diffX2,
                    diffY2 = fm.diffY2,
                    startClientCycles = fm.startClientCycles,
                    speedClientCycles = fm.speedClientCycles,
                    direction = fm.direction,
                )
            },
            faceEntity = faceEntity,
            persistentFaceEntity = persistentFaceEntity,
            hits = hits,
            modelOverrides = modelOverrides,
        )

        val movementAnimations: MovementAnimations? = p.nextAnimation?.let { anim ->
            MovementAnimations(
                ids = anim.ids,
                speed = anim.speed,
            )
        }

        val appearance: PlayerAppearance? = p.appearance.appeareanceData?.let { data ->
            val hash = p.appearance.mD5AppeareanceDataHash ?: return@let null
            PlayerAppearance(
                hash = hash,
                data = data,
            )
        }

        val forceTalk: String? = p.nextForceTalk?.text()

        val movementTypeIdValue = if (p.movementType == MoveType.RUN) 2 else 1
        val movementTypeId: Int? = if (p.isUpdateMovementType) movementTypeIdValue else null
        val persistentMovementTypeId: Int = movementTypeIdValue

        val temporaryMoveTypeId: Int? = p.temporaryMoveType?.id

        return PlayerUpdateBlocks(
            common = common,
            appearance = appearance,
            movementAnimations = movementAnimations,
            faceDirection = faceDirection,
            persistentFaceDirection = persistentFaceDirection,
            movementTypeId = movementTypeId,
            persistentMovementTypeId = persistentMovementTypeId,
            temporaryMoveTypeId = temporaryMoveTypeId,
            forceTalk = forceTalk,
            publicChat = null,
        )
    }

    fun resolvePlayerHits(observer: Player, target: Player): HitsUpdate? {
        if (target.nextHits.isEmpty() && target.nextHitBars.isEmpty()) return null

        val splats = Array<HitSplat>(target.nextHits.size) { i ->
            val hit = target.nextHits[i]
            resolveHitSplat(hit, observer, target)
        }

        val bars = Array<HitBarUpdate>(target.nextHitBars.size) { i ->
            val bar = target.nextHitBars[i]
            resolveHitBar(bar, observer)
        }

        return HitsUpdate(
            hitSplats = splats,
            hitBars = bars,
        )
    }

    fun convertSpotAnim(spotAnim: LegacySpotAnim?): CoreSpotAnim? {
        if (spotAnim == null) return null
        return CoreSpotAnim(
            id = spotAnim.id,
            height = spotAnim.settingsHash shr 16,
            speed = spotAnim.settingsHash and 0xFFFF,
            rotation = spotAnim.settings2Hash and 0x7,
            adjustReplayMode = (spotAnim.settings2Hash and 0x80) != 0,
        )
    }
}
