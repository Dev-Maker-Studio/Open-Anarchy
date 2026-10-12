package org.darkan.tools.cache.map.environment

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import java.util.concurrent.ConcurrentHashMap

object MapEnvironmentLoader {

    private val cached = ConcurrentHashMap<Int, RegionEnvironment>()

    private fun key(regionX: Int, regionY: Int) = (regionX shl 8) or regionY

    fun load(cache: Cache, regionX: Int, regionY: Int): RegionEnvironment? {
        cached[key(regionX, regionY)]?.let { return it }

        val archive = cache.archiveId(Index.MAPS, "m${regionX}_$regionY")
        if (archive == -1) return null
        val data = cache.data(Index.MAPS, archive) ?: return null

        val decoded = MapEnvironmentDecoder.decode(data, regionX, regionY) { id ->
            EnvironmentConfigs.light(cache, id)
        }
        cached[key(regionX, regionY)] = decoded
        return decoded
    }

    fun store(regionX: Int, regionY: Int, environment: RegionEnvironment) {
        cached[key(regionX, regionY)] = environment
    }

    fun environment(cache: Cache, regionX: Int, regionY: Int, chunkX: Int, chunkY: Int): MapEnvironment =
        load(cache, regionX, regionY)?.environment(chunkX, chunkY) ?: MapEnvironment.DEFAULT

    fun cubeTexture(cache: Cache, environment: MapEnvironment): CubeTextureRef? =
        environment.cubeTexture ?: EnvironmentConfigs.defaultCubeTexture(cache)

    fun skybox(cache: Cache, environment: MapEnvironment): SkyboxType? =
        environment.skybox?.let { EnvironmentConfigs.skybox(cache, it.id) }

    fun suns(cache: Cache, skybox: SkyboxType): List<SunType> {
        val ids = skybox.sunIds ?: return emptyList()
        return ids.map { EnvironmentConfigs.sun(cache, it) }
    }

    fun clear() {
        cached.clear()
        EnvironmentConfigs.clear()
    }
}
