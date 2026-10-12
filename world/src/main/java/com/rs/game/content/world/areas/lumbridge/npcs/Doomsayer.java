package com.rs.game.content.world.areas.lumbridge.npcs;

import com.rs.engine.dialogue.Conversation;
import com.rs.engine.dialogue.HeadE;
import com.rs.game.model.entity.player.Player;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.handlers.NPCClickHandler;

@PluginEventHandler
public class Doomsayer extends Conversation {
	private static final int npcId = 3777;
	
	public static NPCClickHandler Doomsayer = new NPCClickHandler(new Object[] { npcId }, e -> {
		switch(e.getOption()) {
		case "Talk-to" -> e.getPlayer().startConversation(new Doomsayer(e.getPlayer()));
		}
	});

	public Doomsayer(Player player) {
		super(player);
		addNPC(npcId, HeadE.SCARED, "Dooooom!")
		.addPlayer( HeadE.WORRIED, "Do you mean the Battle of Lumbridge? Are you telling me I should go and help out by going to join in?")
		.addNPC(npcId, HeadE.CONFUSED,"No, why should I be doing that? I'm talking about doooooom here, not some battlefield.")
		.addPlayer(HeadE.CONFUSED, "Well, everyone else seems to be... um... anyway, you mentioned doom. Where is this doom?")
		.addNPC(npcId, HeadE.SCARED, "All around us! I can feel it in the air, hear it on the wind, smell it...also in the air!")
		.addPlayer(HeadE.CONFUSED, "Is there anything we can do about this doom?")
		.addNPC(npcId, HeadE.HAPPY_TALKING, "There is nothing you need to do my friend! I am the Doomsayer, although my real title could be something like the Danger Tutor.")
		.addPlayer(HeadE.CONFUSED, "Danger Tutor?")
		.addNPC(npcId, HeadE.CHEERFUL, "Yes! I roam the world sensing danger.")
		.addNPC(npcId, HeadE.CHEERFUL, " If I find a dangerous area, then I put up warning signs that will tell you what is so dangerous about that area.");
		create();
	}
}


