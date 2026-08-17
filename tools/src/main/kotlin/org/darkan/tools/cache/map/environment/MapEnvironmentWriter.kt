package org.darkan.tools.cache.map.environment

import org.darkan.core.EnvVars
import org.darkan.tools.cache.CacheArchiveWriter
import org.darkan.tools.cache.ReferenceTable
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes an edited region environment back into the `m{x}_{y}` archive it came from.
 *
 * This is the only code in the tools that mutates the user's cache, so every step is
 * guarded:
 *
 * 1. **Planning is separate from writing.** [plan] does all the decoding, merging and
 *    verification and produces a [EnvironmentWritePlan] describing exactly what would change.
 *    Nothing touches the filesystem until [apply] is called, which is what the CLI's dry-run
 *    mode and the editor's diff preview both use.
 * 2. **Per-region round trip before every write.** The current archive is decoded and
 *    re-encoded *unchanged* first; unless that reproduces the original bytes exactly, the
 *    plan is marked blocked and [apply] refuses. The cache-wide proof is `tools env
 *    roundtrip`, but a save also re-proves it for the one region it is about to overwrite.
 * 3. **Tile bytes are spliced, never re-encoded.** Only the effects block at the tail is
 *    regenerated; the `4 * 64 * 64` tile records in front of it are copied verbatim.
 * 4. **The original is backed up before the first write of a session**, as the raw container
 *    bytes, which is exactly what a restore needs.
 * 5. **Read-back verification.** After writing, the archive is read straight off disk again
 *    and compared byte for byte with what was meant to be written.
 *
 * Saving is always explicit. Nothing here is wired to region changes, panel edits or
 * shutdown.
 */
object MapEnvironmentWriter {

    private const val BACKUP_DIR_PROPERTY = "darkan.cacheBackupDir"

    /** Archives already backed up this session, so a re-save cannot overwrite the pristine copy. */
    private val backedUp: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /**
     * Payloads written this session, keyed by archive id, and the CRC of the container they
     * went into.
     *
     * The live [Cache] memoises decompressed archives and raw sectors with no invalidation
     * hook, so after a save it keeps handing out the pre-save bytes. Planning a second save
     * off those would merge the new edits into the *old* structure and quietly undo the
     * first. The CRC is the plan's identity token: [apply] compares it against a fresh read
     * of the container on disk and refuses if anything moved underneath it.
     */
    private val written = ConcurrentHashMap<Int, ByteArray>()
    private val writtenContainerCrc = ConcurrentHashMap<Int, Int>()

    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** Directory backups land in. Overridable with `-D$BACKUP_DIR_PROPERTY`. */
    fun backupDirectory(): File {
        System.getProperty(BACKUP_DIR_PROPERTY)?.let { return File(it) }
        val working = File(System.getProperty("user.dir"))
        val tools = File(working, "tools")
        return File(if (tools.isDirectory) tools else working, "cache-backups")
    }

    fun cacheDirectory(): File = File(EnvVars.cachePath)

    /**
     * Work out what saving [edit] into `m{regionX}_{regionY}` would do, without writing.
     *
     * Returns a plan whose [EnvironmentWritePlan.blocked] is non-null when the write must not
     * proceed - a missing archive, a round-trip failure, or a merge that does not survive a
     * decode/encode cycle of its own.
     */
    fun plan(cache: Cache, regionX: Int, regionY: Int, edit: EnvironmentEdit): EnvironmentWritePlan {
        val name = "m${regionX}_$regionY"
        val archiveId = cache.archiveId(Index.MAPS, name)
        if (archiveId == -1) {
            return blocked(regionX, regionY, -1, name, "region ($regionX, $regionY) has no map archive")
        }
        val original = written[archiveId]
            ?: cache.data(Index.MAPS, archiveId)
            ?: return blocked(regionX, regionY, archiveId, name, "map archive $archiveId has no data")
        val containerCrc = writtenContainerCrc[archiveId]
            ?: cache.sector(Index.MAPS, archiveId)?.let { CacheArchiveWriter.containerCrc(it, 0) }

        val layout = try {
            MapEnvironmentDecoder.readLayout(original, regionX, regionY)
        } catch (exception: Exception) {
            return blocked(regionX, regionY, archiveId, name, "effects block does not decode: ${exception.message}")
        }

        // Guard 2: prove the encoder is byte-exact for this archive before trusting it with it.
        val reEncoded = MapEnvironmentEncoder.encodeArchive(original, layout)
        if (!reEncoded.contentEquals(original)) {
            return blocked(
                regionX, regionY, archiveId, name,
                "re-encoding $name unchanged does not reproduce the original bytes " +
                    "(${original.size} -> ${reEncoded.size}); refusing to write",
            )
        }

        // The merge only ever touches the first opcode-0 / opcode-128 block, because a later
        // one would overwrite it at decode time and the edit would silently do nothing. No
        // archive in this cache has more than one of either, so refuse rather than guess.
        val environmentBlocks = layout.blocks.count { it is EnvironmentRecord }
        val skyboxBlocks = layout.blocks.count { it is SkyboxRecord }
        if (environmentBlocks > 1 || skyboxBlocks > 1) {
            return blocked(
                regionX, regionY, archiveId, name,
                "$name has $environmentBlocks environment and $skyboxBlocks skybox blocks; " +
                    "merging into a repeated block is not supported",
            )
        }

        val merged = MapEnvironmentEncoder.merge(layout, edit)
        val updated = MapEnvironmentEncoder.encodeArchive(original, merged)

        // Guard: whatever we are about to write must itself decode and round-trip.
        val verify = try {
            MapEnvironmentDecoder.readLayout(updated, regionX, regionY)
        } catch (exception: Exception) {
            return blocked(
                regionX, regionY, archiveId, name,
                "the edited effects block does not decode back: ${exception.message}",
            )
        }
        if (!MapEnvironmentEncoder.encodeArchive(updated, verify).contentEquals(updated)) {
            return blocked(
                regionX, regionY, archiveId, name,
                "the edited effects block does not round-trip; refusing to write",
            )
        }

        return EnvironmentWritePlan(
            regionX = regionX,
            regionY = regionY,
            archiveId = archiveId,
            archiveName = name,
            original = original,
            updated = updated,
            originalLayout = layout,
            updatedLayout = merged,
            changes = describeChanges(layout, merged),
            blocked = null,
            containerCrc = containerCrc,
        )
    }

    /**
     * Perform the write described by [plan]. Throws rather than half-writing.
     *
     * @param patchReferenceTable also update the archive's CRC and version in index 255 so
     *   the cache stays internally consistent for JS5 clients.
     */
    fun apply(plan: EnvironmentWritePlan, patchReferenceTable: Boolean = true): EnvironmentWriteResult {
        check(plan.blocked == null) { "Refusing to apply a blocked plan: ${plan.blocked}" }
        check(plan.hasChanges) { "Nothing to write - the edit matches what is already in the cache" }

        val cacheDir = cacheDirectory()
        val backupDir = backupDirectory()
        val log = ArrayList<String>()

        CacheArchiveWriter(cacheDir).use { writer ->
            val container = writer.readContainer(Index.MAPS, plan.archiveId)
                ?: throw IllegalStateException("Could not read the current container for ${plan.archiveName}")
            val info = CacheArchiveWriter.describeContainer(container)

            // Guard: the plan was computed from a specific set of bytes. If the archive on
            // disk is no longer those bytes, the merge behind this plan is meaningless.
            val onDisk = CacheArchiveWriter.containerCrc(container, 0)
            check(plan.containerCrc == null || plan.containerCrc == onDisk) {
                "${plan.archiveName} changed on disk since the preview was taken " +
                    "(container crc ${plan.containerCrc} vs $onDisk); take a fresh preview"
            }

            backup(backupDir, "${plan.archiveName}.idx${Index.MAPS}.a${plan.archiveId}", container)?.let {
                log.add("backed up ${container.size} container bytes to ${it.absolutePath}")
            }

            val version = archiveVersion(info.trailing)
            val newVersion = if (version >= 0) (version + 1) and 0xffff else -1
            val trailing = if (version >= 0) byteArrayOf((newVersion shr 8).toByte(), newVersion.toByte()) else info.trailing
            val newContainer = CacheArchiveWriter.buildContainer(plan.updated, trailing)

            val startSector = writer.writeContainer(Index.MAPS, plan.archiveId, newContainer)
            log.add(
                "wrote ${newContainer.size} container bytes (${plan.updated.size} payload) " +
                    "for ${plan.archiveName} at sector $startSector"
            )
            if (newVersion >= 0) log.add("archive version $version -> $newVersion")

            // Guard 5: read the archive straight back off disk.
            val readBack = writer.readContainer(Index.MAPS, plan.archiveId)
                ?: throw IllegalStateException("Wrote ${plan.archiveName} but could not read it back")
            check(readBack.contentEquals(newContainer)) {
                "Read-back of ${plan.archiveName} does not match what was written " +
                    "(${readBack.size} vs ${newContainer.size} bytes)"
            }
            val readBackInfo = CacheArchiveWriter.describeContainer(readBack)
            val payload = readBack.copyOfRange(
                readBackInfo.payloadOffset,
                readBackInfo.payloadOffset + readBackInfo.payloadLength,
            )
            check(payload.contentEquals(plan.updated)) { "Read-back payload of ${plan.archiveName} differs" }
            log.add("read-back verified")
            // The next plan for this archive has to start from these bytes, not the stale
            // ones the live Cache is still memoising.
            writtenContainerCrc[plan.archiveId] = CacheArchiveWriter.containerCrc(newContainer, 0)

            if (patchReferenceTable) {
                log.addAll(patchTable(writer, backupDir, plan.archiveId, newContainer, trailing.size, newVersion))
            } else {
                log.add("reference table NOT patched - index 255 CRC for ${plan.archiveName} is now stale")
            }
        }

        // The live Cache still holds the pre-save bytes in its own caches, so seed the
        // environment loader with what was written rather than letting a later read of the
        // stale archive undo the edit in the viewer.
        written[plan.archiveId] = plan.updated
        MapEnvironmentLoader.store(
            plan.regionX,
            plan.regionY,
            MapEnvironmentDecoder.buildRegion(plan.updatedLayout, plan.regionX, plan.regionY),
        )

        return EnvironmentWriteResult(plan, log)
    }

    private fun patchTable(
        writer: CacheArchiveWriter,
        backupDir: File,
        archiveId: Int,
        newContainer: ByteArray,
        trailingSize: Int,
        newVersion: Int,
    ): List<String> {
        val log = ArrayList<String>()
        val tableContainer = writer.readContainer(REFERENCE_TABLE_INDEX, Index.MAPS)
        if (tableContainer == null) {
            log.add("no reference table for index ${Index.MAPS}; CRC left untouched")
            return log
        }
        backup(backupDir, "referencetable.idx${REFERENCE_TABLE_INDEX}.a${Index.MAPS}", tableContainer)?.let {
            log.add("backed up the index ${Index.MAPS} reference table to ${it.absolutePath}")
        }

        val decompressed = ReferenceTable.decompress(tableContainer)
        val table = ReferenceTable.parse(decompressed)
        val crc = CacheArchiveWriter.containerCrc(newContainer, trailingSize)
        val previousCrc = table.crc(archiveId)
        table.setCrc(archiveId, crc)
        if (newVersion >= 0) table.setVersion(archiveId, newVersion)
        table.revision()?.let { table.setRevision(it + 1) }

        val tableInfo = CacheArchiveWriter.describeContainer(tableContainer)
        val rebuilt = CacheArchiveWriter.buildContainer(table.bytes, tableInfo.trailing)
        writer.writeContainer(REFERENCE_TABLE_INDEX, Index.MAPS, rebuilt)

        // Read the table straight back and re-parse it: the CRC that a client validates
        // against is only useful if it survived the round trip through the container.
        val readBack = writer.readContainer(REFERENCE_TABLE_INDEX, Index.MAPS)
            ?: throw IllegalStateException("Wrote the reference table but could not read it back")
        val verified = ReferenceTable.parse(ReferenceTable.decompress(readBack))
        check(verified.crc(archiveId) == crc) {
            "Reference table read-back has crc ${verified.crc(archiveId)}, expected $crc"
        }
        if (newVersion >= 0) {
            check(verified.version(archiveId) == newVersion) {
                "Reference table read-back has version ${verified.version(archiveId)}, expected $newVersion"
            }
        }
        log.add(
            "reference table: archive $archiveId crc " +
                "0x${(previousCrc ?: 0).toUInt().toString(16)} -> 0x${crc.toUInt().toString(16)}" +
                (if (newVersion >= 0) ", version -> $newVersion" else "")
        )
        log.add("reference table rewritten uncompressed (${rebuilt.size} bytes, was ${tableContainer.size})")
        return log
    }

    /** Two-byte archive version from a container's trailing bytes, or -1 when there is none. */
    private fun archiveVersion(trailing: ByteArray): Int =
        if (trailing.size == 2) {
            ((trailing[0].toInt() and 0xff) shl 8) or (trailing[1].toInt() and 0xff)
        } else {
            -1
        }

    private fun backup(directory: File, name: String, container: ByteArray): File? {
        if (!backedUp.add(name)) return null
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create the backup directory ${directory.absolutePath}")
        }
        // Never overwrite an existing backup: two saves inside the same second would
        // otherwise replace the pristine copy with an already-modified one.
        val stamp = LocalDateTime.now().format(timestamp)
        var file = File(directory, "$name.$stamp.container")
        var attempt = 1
        while (file.exists()) {
            file = File(directory, "$name.$stamp-$attempt.container")
            attempt++
        }
        file.writeBytes(container)
        return file
    }

    private fun blocked(
        regionX: Int,
        regionY: Int,
        archiveId: Int,
        name: String,
        reason: String,
    ) = EnvironmentWritePlan(
        regionX = regionX,
        regionY = regionY,
        archiveId = archiveId,
        archiveName = name,
        original = ByteArray(0),
        updated = ByteArray(0),
        originalLayout = EnvironmentLayout.EMPTY,
        updatedLayout = EnvironmentLayout.EMPTY,
        changes = emptyList(),
        blocked = reason,
        containerCrc = null,
    )

    /** Human-readable field-level diff of the effects block. */
    private fun describeChanges(before: EnvironmentLayout, after: EnvironmentLayout): List<String> {
        val changes = ArrayList<String>()
        val old = before.environmentIndex().let { if (it >= 0) before.blocks[it] as EnvironmentRecord else null }
        val new = after.environmentIndex().let { if (it >= 0) after.blocks[it] as EnvironmentRecord else null }

        if (old == null && new != null) changes.add("opcode 0 environment block added")
        if (new != null) {
            // An absent block diffs against the all-flags-clear shape, so the fields that
            // caused it to be added still show up individually.
            val old = old ?: EnvironmentRecord.EMPTY
            colourChange(changes, "sun colour", old.sunColour, new.sunColour, MapEnvironment.DEFAULT_SUN_COLOUR)
            scaledChange(changes, "sun ambient", old.sunAmbient, new.sunAmbient, MapEnvironment.DEFAULT_SUN_AMBIENT)
            scaledChange(changes, "key light", old.sunLight, new.sunLight, MapEnvironment.DEFAULT_SUN_LIGHT)
            scaledChange(changes, "backlight", old.sunBacklight, new.sunBacklight, MapEnvironment.DEFAULT_SUN_BACKLIGHT)
            if (!positionEquals(old.sunPosition, new.sunPosition)) {
                changes.add("sun position: ${position(old.sunPosition)} -> ${position(new.sunPosition)}")
            }
            colourChange(changes, "fog colour", old.fogColour, new.fogColour, MapEnvironment.DEFAULT_FOG_COLOUR)
            if (old.fogDepth != new.fogDepth) {
                changes.add("fog depth: ${old.fogDepth ?: "absent (0)"} -> ${new.fogDepth ?: "absent (0)"}")
            }
            if (old.flags() != new.flags()) {
                changes.add(
                    "flag byte: 0x${old.flags().toString(16).padStart(2, '0')} -> " +
                        "0x${new.flags().toString(16).padStart(2, '0')}"
                )
            }
        }

        val oldSky = before.skyboxIndex().let { if (it >= 0) before.blocks[it] as SkyboxRecord else null }
        val newSky = after.skyboxIndex().let { if (it >= 0) after.blocks[it] as SkyboxRecord else null }
        when {
            oldSky == null && newSky != null ->
                changes.add("skybox: none -> id ${newSky.id} rotation ${newSky.rotation}")
            oldSky != null && newSky == null ->
                changes.add("skybox: id ${oldSky.id} -> none (opcode 128 block removed)")
            oldSky != null && newSky != null && (oldSky.id != newSky.id || oldSky.rotation != newSky.rotation) ->
                changes.add(
                    "skybox: id ${oldSky.id} rotation ${oldSky.rotation} -> " +
                        "id ${newSky.id} rotation ${newSky.rotation}"
                )
        }
        return changes
    }

    private fun colourChange(into: MutableList<String>, label: String, old: Int?, new: Int?, default: Int) {
        if (old == new) return
        into.add("$label: ${colour(old, default)} -> ${colour(new, default)}")
    }

    private fun scaledChange(into: MutableList<String>, label: String, old: Int?, new: Int?, default: Float) {
        if (old == new) return
        into.add("$label: ${scaled(old, default)} -> ${scaled(new, default)}")
    }

    private fun colour(value: Int?, default: Int): String = if (value == null) {
        "absent (0x${(default and 0xffffff).toString(16).padStart(6, '0')})"
    } else {
        "0x${(value and 0xffffff).toString(16).padStart(6, '0')}"
    }

    private fun scaled(value: Int?, default: Float): String = if (value == null) {
        "absent (%.4f)".format(default)
    } else {
        "%.4f (raw $value)".format(value / 256.0f)
    }

    private fun position(value: IntArray?): String =
        if (value == null) "absent" else "(${value[0]}, ${value[1]}, ${value[2]})"

    private fun positionEquals(a: IntArray?, b: IntArray?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> a.contentEquals(b)
    }

    /** Index 255 holds one reference table per index, keyed by the index id. */
    private const val REFERENCE_TABLE_INDEX = 255
}

/** What a save would do, produced without touching the filesystem. */
class EnvironmentWritePlan(
    val regionX: Int,
    val regionY: Int,
    val archiveId: Int,
    val archiveName: String,
    val original: ByteArray,
    val updated: ByteArray,
    val originalLayout: EnvironmentLayout,
    val updatedLayout: EnvironmentLayout,
    /** Field-level description of the difference, one line per changed field. */
    val changes: List<String>,
    /** Non-null when the write must not proceed; [MapEnvironmentWriter.apply] refuses. */
    val blocked: String?,
    /**
     * CRC of the container these bytes came out of, or null when it could not be read.
     * [MapEnvironmentWriter.apply] re-reads the container and refuses if it has moved on.
     */
    val containerCrc: Int?,
) {
    val hasChanges: Boolean get() = !updated.contentEquals(original)

    val effectsSizeBefore: Int get() = original.size - originalLayout.tileSectionLength
    val effectsSizeAfter: Int get() = updated.size - updatedLayout.tileSectionLength

    fun summary(): String = when {
        blocked != null -> "BLOCKED: $blocked"
        !hasChanges -> "no change - $archiveName already matches the editor"
        else -> "$archiveName (archive $archiveId): effects $effectsSizeBefore -> $effectsSizeAfter bytes, " +
            "archive ${original.size} -> ${updated.size} bytes"
    }
}

/** Result of a completed write, with the audit log of every step taken. */
class EnvironmentWriteResult(val plan: EnvironmentWritePlan, val log: List<String>)
