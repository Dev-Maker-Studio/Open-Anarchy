package org.darkan.lobby.command

import org.darkan.core.model.Rights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LobbyCommandsTest {

    @Test
    fun `the password commands are registered at the right tiers`() {
        val player = LobbyCommands.available(Rights.PLAYER).map { it.name }
        val owner = LobbyCommands.available(Rights.OWNER).map { it.name }
        assertTrue("changepass [new password]".substringBefore(' ') in player)
        assertTrue(player.none { it.startsWith("changepassother") })
        assertTrue(owner.any { it.startsWith("changepassother") })
    }

    @Test
    fun `every tier above the registering one can see a command`() {
        val admin = LobbyCommands.available(Rights.ADMIN).map { it.name }
        val developer = LobbyCommands.available(Rights.DEVELOPER).map { it.name }
        assertTrue(admin.none { it.startsWith("changepassother") })
        assertTrue(developer.none { it.startsWith("changepassother") })
        assertEquals(LobbyCommands.available(Rights.PLAYER).size + 1, LobbyCommands.available(Rights.OWNER).size)
    }
}
