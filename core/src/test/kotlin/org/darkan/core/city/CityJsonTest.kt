package org.darkan.core.city

import org.darkan.core.gameval.gen.Loc
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class CityJsonTest {
    private val city = City(
        id = "testburg",
        name = "Testburg",
        seed = 5L,
        centerX = 7500,
        centerY = 7600,
        halfSize = 34,
        baseHeight = 120,
        streets = listOf(Street(7496, 7596, 9, 9)),
        buildings = listOf(
            Building(
                BuildingBuilder.BANK, 7510, 7600, 9, 9, Side.SOUTH.name, 2, WallStyle.BRICK.id, FloorStyle.MARBLE.id,
                BuildingBuilder.plan(BuildingBuilder.BANK, 7510, 7600, 9, 9, Side.SOUTH, 2).furniture,
            ),
        ),
        residents = listOf(Resident(9, 7501, 7602, "guard")),
    )

    @Test
    fun `a city survives encoding and decoding`() {
        assertEquals(city, CityJson.decode(CityJson.encode(city)))
    }

    @Test
    fun `a city survives a trip through a file`() {
        val directory = Files.createTempDirectory("cities").toFile()
        CityJson.write(directory, city)
        assertEquals(listOf(city), CityJson.readAll(directory))
        directory.deleteRecursively()
    }

    @Test
    fun `a bank holds booths and bankers`() {
        val plan = BuildingBuilder.plan(BuildingBuilder.BANK, 0, 0, 9, 9, Side.SOUTH, 1)
        assertEquals(plan.furniture.count { it.loc == "bankbooth" }, plan.residents.count { it.role == "banker" })
    }

    @Test
    fun `every upper floor is reached by a ladder with its top one level up`() {
        val building = city.buildings.single()
        val pieces = building.pieces()
        val foot = pieces.single { it.loc == Loc.ladder }
        val top = pieces.single { it.loc == Loc.laddertop }
        assertEquals(Triple(foot.x, foot.y, foot.level + 1), Triple(top.x, top.y, top.level))
        assertEquals(setOf(0, 1), pieces.map { it.level }.toSet())
    }

    @Test
    fun `a smithy holds a furnace and an anvil that both become pieces`() {
        val furniture = BuildingBuilder.plan(BuildingBuilder.SMITHY, 7510, 7600, 9, 9, Side.SOUTH, 1).furniture
        val smithy = Building(BuildingBuilder.SMITHY, 7510, 7600, 9, 9, Side.SOUTH.name, 1, WallStyle.STONE.id, FloorStyle.SLATE.id, furniture)

        val locs = smithy.pieces().map { it.loc }

        assertEquals(listOf(Loc.built_furnace), locs.filter { it == Loc.built_furnace })
        assertEquals(listOf(Loc.loc_2783), locs.filter { it == Loc.loc_2783 })
    }
}
