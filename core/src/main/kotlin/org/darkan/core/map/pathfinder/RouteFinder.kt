package org.darkan.core.map.pathfinder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.darkan.core.map.WorldCollision
import org.darkan.core.map.pathfinder.collision.CollisionStrategy
import org.darkan.core.map.pathfinder.collision.CollisionStrategyType
import org.darkan.core.map.pathfinder.reach.DefaultReachStrategy
import org.darkan.core.map.pathfinder.reach.ReachStrategy

data class RouteRequest(
    val srcX: Int,
    val srcY: Int,
    val z: Int,
    val destX: Int,
    val destY: Int,
    val srcSize: Int = 1,
    val destWidth: Int = 1,
    val destHeight: Int = 1,
    val objRot: Int = 10,
    val objShape: Int = -1,
    val accessBitMask: Int = 0,
    val maxTurns: Int = 25,
    val collision: CollisionStrategy = CollisionStrategyType.NORMAL.strategy,
    val reachStrategy: ReachStrategy = DefaultReachStrategy,
)

object RouteFinder {

    private val threadLocalPathFinder = ThreadLocal.withInitial {
        PathFinder(flags = WorldCollision.allFlags, useRouteBlockerFlags = true)
    }

    fun findRoute(request: RouteRequest): Route {
        return threadLocalPathFinder.get().findPath(
            srcX = request.srcX,
            srcY = request.srcY,
            destX = request.destX,
            destY = request.destY,
            z = request.z,
            srcSize = request.srcSize,
            destWidth = request.destWidth,
            destHeight = request.destHeight,
            objRot = request.objRot,
            objShape = request.objShape,
            accessBitMask = request.accessBitMask,
            maxTurns = request.maxTurns,
            collision = request.collision,
            reachStrategy = request.reachStrategy,
        )
    }

    fun findRoute(
        srcX: Int,
        srcY: Int,
        z: Int,
        destX: Int,
        destY: Int,
        srcSize: Int = 1,
        destWidth: Int = 1,
        destHeight: Int = 1,
        objRot: Int = 10,
        objShape: Int = -1,
        accessBitMask: Int = 0,
        maxTurns: Int = 25,
        collision: CollisionStrategy = CollisionStrategyType.NORMAL.strategy,
        reachStrategy: ReachStrategy = DefaultReachStrategy,
    ): Route {
        return threadLocalPathFinder.get().findPath(
            srcX = srcX,
            srcY = srcY,
            destX = destX,
            destY = destY,
            z = z,
            srcSize = srcSize,
            destWidth = destWidth,
            destHeight = destHeight,
            objRot = objRot,
            objShape = objShape,
            accessBitMask = accessBitMask,
            maxTurns = maxTurns,
            collision = collision,
            reachStrategy = reachStrategy,
        )
    }

    suspend fun findRoutes(requests: List<RouteRequest>): List<Route> {
        if (requests.isEmpty()) return emptyList()
        if (requests.size == 1) return listOf(findRoute(requests[0]))
        return coroutineScope {
            requests.map { request ->
                async(Dispatchers.Default) { findRoute(request) }
            }.awaitAll()
        }
    }
}
