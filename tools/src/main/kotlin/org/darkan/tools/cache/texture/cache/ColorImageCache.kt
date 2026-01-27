package org.darkan.tools.cache.texture.cache

/**
 * LRU cache for color (RGB) texture operation output.
 * Stores texture data row by row with 3 channels (red, green, blue).
 *
 * @param capacity The number of rows to cache
 * @param totalRows The total number of rows in the texture
 * @param width The width of each row in pixels
 */
class ColorImageCache(
    private val capacity: Int,
    private val totalRows: Int,
    width: Int
) {
    /** True if the current row needs to be regenerated */
    var dirty: Boolean = false

    /** RGB channel data: [cacheIndex][channel][pixelX] */
    private var paletteData: Array<Array<IntArray>> = Array(capacity) {
        Array(3) { IntArray(width) }
    }

    /** Maps row index to cache entry, null if not cached */
    private var rowToEntry: Array<ColorImageCacheEntry?> = arrayOfNulls(totalRows)

    /** LRU list of cache entries */
    private val lruList = mutableListOf<ColorImageCacheEntry>()

    /** Number of cache slots currently in use */
    private var usedSlots: Int = 0

    /** Last used row index (optimization for capacity=1) */
    private var lastRowIndex: Int = -1

    /**
     * Get the RGB palette data for the specified row.
     * Returns an array of 3 IntArrays: [red, green, blue].
     *
     * @param rowIndex The texture row index
     * @return The RGB palette data for this row
     */
    fun getPalette(rowIndex: Int): Array<IntArray> {
        // Case 1: Full cache (capacity == totalRows)
        if (totalRows == capacity) {
            dirty = rowToEntry[rowIndex] == null
            rowToEntry[rowIndex] = SENTINEL
            return paletteData[rowIndex]
        }

        // Case 2: Single-row cache (capacity == 1)
        if (capacity == 1) {
            dirty = lastRowIndex != rowIndex
            lastRowIndex = rowIndex
            return paletteData[0]
        }

        // Case 3: LRU cache
        var entry = rowToEntry[rowIndex]
        if (entry == null) {
            dirty = true
            if (usedSlots >= capacity) {
                // Evict oldest entry
                val oldEntry = lruList.removeAt(lruList.size - 1)
                entry = ColorImageCacheEntry(rowIndex, oldEntry.cacheIndex)
                rowToEntry[oldEntry.rowIndex] = null
            } else {
                entry = ColorImageCacheEntry(rowIndex, usedSlots++)
            }
            rowToEntry[rowIndex] = entry
        } else {
            dirty = false
            lruList.remove(entry)
        }

        // Move to front of LRU list
        lruList.add(0, entry)
        return paletteData[entry.cacheIndex]
    }

    /**
     * Clear and release the cache.
     */
    fun clear() {
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
     * @return All RGB palette data rows
     * @throws RuntimeException if not in full cache mode
     */
    fun getAllPaletteData(): Array<Array<IntArray>> {
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
        private val SENTINEL = ColorImageCacheEntry(0, 0)
    }
}

/**
 * Cache entry that tracks which row is stored at which cache index.
 */
class ColorImageCacheEntry(
    val rowIndex: Int,
    val cacheIndex: Int
)
