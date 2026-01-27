package org.darkan.tools.cache.texture.cache

/**
 * LRU cache for monochrome (single-channel) texture operation output.
 * Stores texture data row by row with 1 channel (grayscale).
 *
 * @param capacity The number of rows to cache
 * @param totalRows The total number of rows in the texture
 * @param width The width of each row in pixels
 */
class MonochromeImageCache(
    private val capacity: Int,
    private val totalRows: Int,
    width: Int
) {
    /** True if the current row needs to be regenerated */
    var dirty: Boolean = false

    /** Grayscale channel data: [cacheIndex][pixelX] */
    private var paletteData: Array<IntArray?> = Array(capacity) { IntArray(width) }

    /** Maps row index to cache entry, null if not cached */
    private var rowToEntry: Array<MonochromeImageCacheEntry?> = arrayOfNulls(totalRows)

    /** LRU list of cache entries */
    private val lruList = mutableListOf<MonochromeImageCacheEntry>()

    /** Number of cache slots currently in use */
    private var usedSlots: Int = 0

    /** Last used row index (optimization for capacity=1) */
    private var lastRowIndex: Int = -1

    /**
     * Get the grayscale palette data for the specified row.
     *
     * @param rowIndex The texture row index
     * @return The grayscale palette data for this row
     */
    fun getPalette(rowIndex: Int): IntArray {
        // Case 1: Full cache (capacity == totalRows)
        if (totalRows == capacity) {
            dirty = rowToEntry[rowIndex] == null
            rowToEntry[rowIndex] = SENTINEL
            return paletteData[rowIndex]!!
        }

        // Case 2: Single-row cache (capacity == 1)
        if (capacity == 1) {
            dirty = lastRowIndex != rowIndex
            lastRowIndex = rowIndex
            return paletteData[0]!!
        }

        // Case 3: LRU cache
        var entry = rowToEntry[rowIndex]
        if (entry == null) {
            dirty = true
            if (usedSlots >= capacity) {
                // Evict oldest entry
                val oldEntry = lruList.removeAt(lruList.size - 1)
                entry = MonochromeImageCacheEntry(rowIndex, oldEntry.cacheIndex)
                rowToEntry[oldEntry.rowIndex] = null
            } else {
                entry = MonochromeImageCacheEntry(rowIndex, usedSlots++)
            }
            rowToEntry[rowIndex] = entry
        } else {
            dirty = false
            lruList.remove(entry)
        }

        // Move to front of LRU list
        lruList.add(0, entry)
        return paletteData[entry.cacheIndex]!!
    }

    /**
     * Reset and release the cache.
     */
    fun reset() {
        for (i in paletteData.indices) {
            paletteData[i] = null
        }
        paletteData = emptyArray()
        rowToEntry = emptyArray()
        lruList.clear()
        usedSlots = 0
        lastRowIndex = -1
    }

    /**
     * Gets all palette data (requires full cache mode where capacity == totalRows).
     * Marks all rows as initialized.
     *
     * @return All palette data rows
     * @throws RuntimeException if not in full cache mode
     */
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
        /** Sentinel entry used for full cache mode */
        private val SENTINEL = MonochromeImageCacheEntry(0, 0)
    }
}

/**
 * Cache entry that tracks which row is stored at which cache index.
 */
class MonochromeImageCacheEntry(
    val rowIndex: Int,
    val cacheIndex: Int
)
