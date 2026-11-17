package org.darkan.core.city

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
            BuildingBuilder.build(BuildingBuilder.BANK, 7510, 7600, 9, 9, Side.SOUTH).let {
                Building(BuildingBuilder.BANK, 7510, 7600, 9, 9, Side.SOUTH.name, it.pieces)
            },
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
        val result = BuildingBuilder.build(BuildingBuilder.BANK, 0, 0, 9, 9, Side.SOUTH)
        assertEquals(result.pieces.count { it.loc == org.darkan.core.gameval.gen.Loc.bankbooth }, result.residents.count { it.role == "banker" })
    }
}
