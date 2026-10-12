package org.darkan.tools.cache.map.environment

import org.darkan.core.EnvVars
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.FileCache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.secure.CRC
import world.gregs.voidps.cache.store.Container
import world.gregs.voidps.cache.store.FileStore
import world.gregs.voidps.cache.store.ReferenceTable
import java.io.File
import java.math.BigInteger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object MapEnvironmentWriter {

    private const val BACKUP_DIR_PROPERTY = "darkan.cacheBackupDir"

    private val backedUp: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val written = ConcurrentHashMap<Int, ByteArray>()
    private val writtenContainerCrc = ConcurrentHashMap<Int, Int>()

    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun backupDirectory(): File {
        System.getProperty(BACKUP_DIR_PROPERTY)?.let { return File(it) }
        val working = File(System.getProperty("user.dir"))
        val tools = File(working, "tools")
        return File(if (tools.isDirectory) tools else working, "cache-backups")
    }

    fun cacheDirectory(): File = File(EnvVars.cachePath)

    fun plan(cache: Cache, regionX: Int, regionY: Int, edit: EnvironmentEdit): EnvironmentWritePlan {
        val name = "m${regionX}_$regionY"
        val archiveId = cache.archiveId(Index.MAPS, name)
        if (archiveId == -1) {
            return blocked(regionX, regionY, -1, name, "region ($regionX, $regionY) has no map archive")
        }
        val files = cache.files(Index.MAPS, archiveId)
        if (files.size != 1) {
            return blocked(
                regionX, regionY, archiveId, name,
                "$name has ${files.size} files; the writer only handles a single-file map archive",
            )
        }
        val fileId = files[0]
        val original = written[archiveId]
            ?: cache.data(Index.MAPS, archiveId, fileId)
            ?: return blocked(regionX, regionY, archiveId, name, "map archive $archiveId has no data")
        val containerCrc = writtenContainerCrc[archiveId]
            ?: cache.sector(Index.MAPS, archiveId)?.let { token(it) }

        val layout = try {
            MapEnvironmentDecoder.readLayout(original, regionX, regionY)
        } catch (exception: Exception) {
            return blocked(regionX, regionY, archiveId, name, "effects block does not decode: ${exception.message}")
        }

        val reEncoded = MapEnvironmentEncoder.encodeArchive(original, layout)
        if (!reEncoded.contentEquals(original)) {
            return blocked(
                regionX, regionY, archiveId, name,
                "re-encoding $name unchanged does not reproduce the original bytes " +
                    "(${original.size} -> ${reEncoded.size}); refusing to write",
            )
        }

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
            fileId = fileId,
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

    fun apply(plan: EnvironmentWritePlan): EnvironmentWriteResult {
        check(plan.blocked == null) { "Refusing to apply a blocked plan: ${plan.blocked}" }
        check(plan.hasChanges) { "Nothing to write - the edit matches what is already in the cache" }

        val cacheDir = cacheDirectory()
        val backupDir = backupDirectory()
        val log = ArrayList<String>()

        val cache = FileCache.load(
            cacheDir.path,
            BigInteger(EnvVars.js5RsaExponent),
            BigInteger(EnvVars.js5RsaModulus),
            writable = true,
        )
        try {
            val container = cache.container(Index.MAPS, plan.archiveId)
                ?: throw IllegalStateException("Could not read the current container for ${plan.archiveName}")

            val onDisk = token(container)
            check(plan.containerCrc == null || plan.containerCrc == onDisk) {
                "${plan.archiveName} changed on disk since the preview was taken " +
                    "(container crc ${plan.containerCrc} vs $onDisk); take a fresh preview"
            }

            backup(backupDir, "${plan.archiveName}.idx${Index.MAPS}.a${plan.archiveId}", container)?.let {
                log.add("backed up ${container.size} container bytes to ${it.absolutePath}")
            }
            val tableBefore = cache.container(FileStore.REFERENCE_INDEX, Index.MAPS)
            if (tableBefore != null) {
                backup(backupDir, "referencetable.idx${FileStore.REFERENCE_INDEX}.a${Index.MAPS}", tableBefore)?.let {
                    log.add("backed up the index ${Index.MAPS} reference table to ${it.absolutePath}")
                }
            }

            val compression = Container.decode(container).compression
            val version = cache.archiveVersion(Index.MAPS, plan.archiveId)
            val crc = cache.archiveCrc(Index.MAPS, plan.archiveId)

            cache.write(Index.MAPS, plan.archiveId, plan.fileId, plan.updated)

            val readBack = cache.container(Index.MAPS, plan.archiveId)
                ?: throw IllegalStateException("Wrote ${plan.archiveName} but could not read it back")
            val decoded = Container.decode(readBack)
            check(decoded.compression == compression) {
                "Read-back of ${plan.archiveName} is ${decoded.compression}, not the $compression it was stored as"
            }
            check(decoded.data().contentEquals(plan.updated)) {
                "Read-back payload of ${plan.archiveName} differs (${decoded.decompressedSize} vs ${plan.updated.size} bytes)"
            }
            val newVersion = cache.archiveVersion(Index.MAPS, plan.archiveId)
            check(newVersion == version + 1) {
                "${plan.archiveName} went from version $version to $newVersion; a write bumps it by one"
            }
            check(decoded.version == (newVersion and 0xffff)) {
                "${plan.archiveName}'s container trailer says ${decoded.version}, not ${newVersion and 0xffff}"
            }
            val newCrc = CRC.calculate(readBack, 0, readBack.size - if (decoded.version == null) 0 else TRAILER)
            log.add(
                "wrote ${readBack.size} container bytes (${plan.updated.size} payload, " +
                    "recompressed as ${compression.name.lowercase()}) for ${plan.archiveName}"
            )
            log.add("read-back verified")
            log.add("archive version $version -> $newVersion")

            log.addAll(verifyTable(cache, plan, newCrc, newVersion, crc))

            writtenContainerCrc[plan.archiveId] = token(readBack)
        } finally {
            cache.close()
        }

        written[plan.archiveId] = plan.updated
        MapEnvironmentLoader.store(
            plan.regionX,
            plan.regionY,
            MapEnvironmentDecoder.buildRegion(plan.updatedLayout, plan.regionX, plan.regionY),
        )

        return EnvironmentWriteResult(plan, log)
    }

    private fun verifyTable(
        cache: Cache,
        plan: EnvironmentWritePlan,
        crc: Int,
        version: Int,
        previousCrc: Int,
    ): List<String> {
        val log = ArrayList<String>()
        val container = cache.container(FileStore.REFERENCE_INDEX, Index.MAPS)
            ?: throw IllegalStateException("Wrote ${plan.archiveName} but index ${Index.MAPS} has no reference table")
        val decoded = Container.decode(container)
        val table = ReferenceTable.decode(decoded.data())
        val entry = table.archive(plan.archiveId)
            ?: throw IllegalStateException("The reference table read back has no entry for archive ${plan.archiveId}")
        check(entry.crc == crc) {
            "Reference table read-back has crc ${entry.crc}, expected $crc"
        }
        check(entry.version == version) {
            "Reference table read-back has version ${entry.version}, expected $version"
        }
        check(entry.fileIds.contentEquals(intArrayOf(plan.fileId))) {
            "Reference table read-back lists files ${entry.fileIds.toList()} for archive ${plan.archiveId}, " +
                "expected only ${plan.fileId}"
        }
        log.add(
            "reference table: archive ${plan.archiveId} crc " +
                "0x${previousCrc.toUInt().toString(16)} -> 0x${crc.toUInt().toString(16)}, version -> $version"
        )
        log.add(
            "reference table rewritten as ${decoded.compression.name.lowercase()} (${container.size} bytes), " +
                "version table re-signed"
        )
        return log
    }

    private fun backup(directory: File, name: String, container: ByteArray): File? {
        if (!backedUp.add(name)) return null
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create the backup directory ${directory.absolutePath}")
        }
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
        fileId = 0,
        archiveName = name,
        original = ByteArray(0),
        updated = ByteArray(0),
        originalLayout = EnvironmentLayout.EMPTY,
        updatedLayout = EnvironmentLayout.EMPTY,
        changes = emptyList(),
        blocked = reason,
        containerCrc = null,
    )

    private fun describeChanges(before: EnvironmentLayout, after: EnvironmentLayout): List<String> {
        val changes = ArrayList<String>()
        val old = before.environmentIndex().let { if (it >= 0) before.blocks[it] as EnvironmentRecord else null }
        val new = after.environmentIndex().let { if (it >= 0) after.blocks[it] as EnvironmentRecord else null }

        if (old == null && new != null) changes.add("opcode 0 environment block added")
        if (new != null) {
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

    private fun token(container: ByteArray): Int = CRC.calculate(container, 0, container.size)

    private const val TRAILER = 2
}

class EnvironmentWritePlan(
    val regionX: Int,
    val regionY: Int,
    val archiveId: Int,
    val fileId: Int,
    val archiveName: String,
    val original: ByteArray,
    val updated: ByteArray,
    val originalLayout: EnvironmentLayout,
    val updatedLayout: EnvironmentLayout,
    val changes: List<String>,
    val blocked: String?,
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

class EnvironmentWriteResult(val plan: EnvironmentWritePlan, val log: List<String>)
