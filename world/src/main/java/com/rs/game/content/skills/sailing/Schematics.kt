package com.rs.game.content.skills.sailing

import com.rs.plugin.annotations.ServerStartupEvent
import com.rs.plugin.kts.onItemClick
import org.darkan.core.EnvVars
import org.darkan.core.gameval.gen.Obj
import org.darkan.core.model.Item

@ServerStartupEvent
fun mapSchematics() {
    if (!EnvVars.generateMaps) return
    listOf(Obj.sailing_eternal_schematic to Schematic.ETERNAL, Obj.sailing_abyssal_schematic to Schematic.ABYSSAL).forEach { (item, schematic) ->
        onItemClick(item, options = arrayOf("Read")) { e ->
            val profile = SailorStore.of(e.player.username)
            if (profile.unlocked(schematic)) return@onItemClick e.player.sendMessage("You already know this schematic.")
            profile.unlocks.add(schematic.name)
            SailorStore.save(profile)
            e.player.inventory.deleteItem(e.slotId, Item(item, 1))
            e.player.sendMessage("You study the schematic. A Shipwright can now build ${schematic.name.lowercase()} hulls, masts and keels for you, if your levels allow.")
        }
    }
}
