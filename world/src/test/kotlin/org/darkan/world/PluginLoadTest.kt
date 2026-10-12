package org.darkan.world

import com.rs.plugin.PluginManager
import com.rs.plugin.events.ButtonClickEvent
import com.rs.plugin.events.LoginEvent
import org.darkan.world.harness.PluginLoadProbe
import org.darkan.world.harness.WorldHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginLoadTest {

    @Test
    fun everyPluginHandlerFieldRegisters() {
        WorldHarness.boot()
        val result = PluginLoadProbe.probe()
        result.failures.forEach { println("plugin registration failure: ${it.owner}#${it.field}: ${it.error}") }
        assertTrue(result.failures.isEmpty(), "plugin handler fields failed to register: ${result.failures}")
        assertTrue(result.registered > 1000, "only ${result.registered} handler fields registered")
        assertTrue(result.eventTypes > 20, "only ${result.eventTypes} plugin event types were discovered")
    }

    @Test
    fun pluginRegistrationReportedNoFailures() {
        WorldHarness.boot()
        assertEquals(0, PluginManager.getRegistrationFailures(), "PluginManager reported registration failures")
    }

    @Test
    fun pluginManagerRoutesBothKeyedAndGlobalEvents() {
        val harness = WorldHarness.login("routing")
        try {
            assertTrue(
                PluginManager.handle(ButtonClickEvent(harness.player, 182, 6, -1, -1, 1)),
                "keyed ButtonClickEvent routing found no handler for interface 182"
            )
            assertTrue(
                PluginManager.handle(LoginEvent(harness.player)),
                "global LoginEvent routing found no handler"
            )
        } finally {
            WorldHarness.logout(harness)
        }
    }
}
