package org.darkan.tools.cache

import world.gregs.voidps.cache.Cache

object NameLookup {

    fun nameHash(name: String): Int = name.hashCode()

    fun archiveId(cache: Cache, index: Int, name: String): Int = cache.archiveId(index, name)

    fun fileId(cache: Cache, index: Int, archive: Int, name: String): Int = cache.fileId(index, archive, name)

    fun archive(cache: Cache, index: Int, id: Int): ByteArray? = cache.data(index, id)

    fun archive(cache: Cache, index: Int, name: String): ByteArray? = cache.data(index, name)

    fun file(cache: Cache, index: Int, archive: Int, file: Int): ByteArray? = cache.data(index, archive, file)

    fun file(cache: Cache, index: Int, archive: String, file: String): ByteArray? = cache.data(index, archive, file)

    data class IndexNames(
        val index: Int,
        val hasNameFlag: Boolean,
        val archiveNameCount: Int,
        val fileNameCount: Int
    ) {
        val hasArchiveNames get() = archiveNameCount > 0

        val hasFileNames get() = fileNameCount > 0
    }

    fun scanNames(cache: Cache): List<IndexNames> = cache.indices().map { index ->
        IndexNames(
            index = index,
            hasNameFlag = cache.hasNameFlag(index),
            archiveNameCount = cache.archiveNameCount(index),
            fileNameCount = cache.fileNameCount(index)
        )
    }
}
