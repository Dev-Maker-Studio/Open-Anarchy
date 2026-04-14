// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
//  Copyright (C) 2021 Trenton Kress
//  This file is part of project: Darkan
//
package com.rs.engine.pathfinder

import com.rs.game.model.entity.Entity
import com.rs.game.model.entity.player.Player
import org.darkan.core.map.pathfinder.Direction
import org.darkan.core.model.Vec2
import world.gregs.voidps.type.Tile

fun Direction.Companion.getFaceDirection(faceTile: Tile, player: Player): Direction {
    if (player.x < faceTile.x) return Direction.EAST
    if (player.x > faceTile.x) return Direction.WEST
    return if (player.y < faceTile.y) Direction.NORTH
    else if (player.y > faceTile.y) Direction.SOUTH
    else Direction.NORTH
}

fun Direction.Companion.getDirectionTo(entity: Entity, target: Any): Direction? {
    val from = entity.middleTileAsVector
    val to = if (target is Entity) target.middleTileAsVector else Vec2(target as Tile)
    val norm = to.sub(from).norm()
    val delta = norm.toTile()
    return Direction.forDelta(delta.x, delta.y)
}
