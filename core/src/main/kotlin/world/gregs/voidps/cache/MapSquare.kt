package world.gregs.voidps.cache

import world.gregs.voidps.type.Region

/**
 * The names of the five [Index.MAPS] archives that make up one map square (region).
 *
 * Index 5 is the one index whose archive names are built rather than catalogued: there is no
 * `gamevals/mapsquare.json` and there never will be, because the name is a pure function of the
 * region coordinates. The 727 spelling has no separator between the letter and the first
 * coordinate - `m50_50`, not `m_50_50` - and only the two coordinates are separated by `_`.
 *
 * The formula is the client's own: `MapRegion.getArchiveName(floorData, underwater, regionX,
 * regionY)` composes `"m"`/`"l"` + regionX + `'_'` + regionY and prefixes `"u"` when underwater,
 * and its callers ask for the npc spawn archive as `"n" + regionX + '_' + regionY`.
 *
 * Names resolve to archive ids through [Cache.archiveId], which hashes the name the same way the
 * reference table did. Not every region has all five: only 38 of the 2,407 surface squares carry
 * npc spawns, and 1,208 carry an underwater pair.
 */
object MapSquare {

    /** Terrain, height and overlay/underlay data: `m<regionX>_<regionY>`. */
    fun terrain(regionX: Int, regionY: Int): String = "m${regionX}_$regionY"

    /** Placed locations: `l<regionX>_<regionY>`. XTEA encrypted. */
    fun locations(regionX: Int, regionY: Int): String = "l${regionX}_$regionY"

    /** Npc spawns: `n<regionX>_<regionY>`. */
    fun npcs(regionX: Int, regionY: Int): String = "n${regionX}_$regionY"

    /** The underwater map's terrain: `um<regionX>_<regionY>`. */
    fun underwaterTerrain(regionX: Int, regionY: Int): String = "um${regionX}_$regionY"

    /** The underwater map's locations: `ul<regionX>_<regionY>`. */
    fun underwaterLocations(regionX: Int, regionY: Int): String = "ul${regionX}_$regionY"

    /** [terrain] of [region]. */
    fun terrain(region: Region): String = terrain(region.x, region.y)

    /** [locations] of [region]. */
    fun locations(region: Region): String = locations(region.x, region.y)

    /** [npcs] of [region]. */
    fun npcs(region: Region): String = npcs(region.x, region.y)

    /** [underwaterTerrain] of [region]. */
    fun underwaterTerrain(region: Region): String = underwaterTerrain(region.x, region.y)

    /** [underwaterLocations] of [region]. */
    fun underwaterLocations(region: Region): String = underwaterLocations(region.x, region.y)
}
