package org.darkan.tools.cache.texture.cache

import org.darkan.tools.cache.texture.TextureGenerationException

class MonochromeImageCache(
    private val capacity: Int,
    private val totalRows: Int,
    width: Int,
    private val deadlineNanos: Long = 0L
) {
    var dirty: Boolean = false

    private var paletteData: Array<IntArray?> = Array(capacity) { IntArray(width) }

    private var rowToEntry: Array<MonochromeImageCacheEntry?> = arrayOfNulls(totalRows)

    private var head: MonochromeImageCacheEntry? = null

    private var tail: MonochromeImageCacheEntry? = null

    private var usedSlots: Int = 0

    private var lastRowIndex: Int = -1

    fun getPalette(rowIndex: Int): IntArray {
        if (deadlineNanos != 0L && System.nanoTime() > deadlineNanos) {
            throw TextureGenerationException("Texture generation timeout in cache access")
        }
        if (totalRows == capacity) {
            dirty = rowToEntry[rowIndex] == null
            rowToEntry[rowIndex] = SENTINEL
            return paletteData[rowIndex]!!
        }

        if (capacity == 1) {
            dirty = lastRowIndex != rowIndex
            lastRowIndex = rowIndex
            return paletteData[0]!!
        }

        var entry = rowToEntry[rowIndex]
        if (entry == null) {
            dirty = true
            if (usedSlots >= capacity) {
                val oldEntry = removeTail()!!
                entry = MonochromeImageCacheEntry(rowIndex, oldEntry.cacheIndex)
                rowToEntry[oldEntry.rowIndex] = null
            } else {
                entry = MonochromeImageCacheEntry(rowIndex, usedSlots++)
            }
            rowToEntry[rowIndex] = entry
        } else {
            dirty = false
            unlink(entry)
        }

        insertAtHead(entry)
        return paletteData[entry.cacheIndex]!!
    }

    private fun unlink(entry: MonochromeImageCacheEntry) {
        val p = entry.prev
        val n = entry.next
        if (p != null) p.next = n else head = n
        if (n != null) n.prev = p else tail = p
        entry.prev = null
        entry.next = null
    }

    private fun insertAtHead(entry: MonochromeImageCacheEntry) {
        entry.prev = null
        entry.next = head
        if (head != null) head!!.prev = entry
        head = entry
        if (tail == null) tail = entry
    }

    private fun removeTail(): MonochromeImageCacheEntry? {
        val t = tail ?: return null
        unlink(t)
        return t
    }

    fun reset() {
        for (i in paletteData.indices) {
            paletteData[i] = null
        }
        paletteData = emptyArray()
        rowToEntry = emptyArray()
        head = null
        tail = null
        usedSlots = 0
        lastRowIndex = -1
    }

    fun getAllPaletteData(): Array<IntArray?> {
        if (capacity != totalRows) {
            throw RuntimeException("getAllPaletteData requires full cache mode")
        }
        for (i in 0 until capacity) {
            rowToEntry[i] = SENTINEL
        }
        return paletteData
    }

    companion object {
        private val SENTINEL = MonochromeImageCacheEntry(0, 0)
    }
}

class MonochromeImageCacheEntry(
    val rowIndex: Int,
    val cacheIndex: Int
) {
    var prev: MonochromeImageCacheEntry? = null
    var next: MonochromeImageCacheEntry? = null
}
