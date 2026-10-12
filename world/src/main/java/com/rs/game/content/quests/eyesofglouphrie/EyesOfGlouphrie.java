package com.rs.game.content.quests.eyesofglouphrie;

import com.rs.engine.quest.Quest;
import com.rs.engine.quest.QuestHandler;
import com.rs.engine.quest.QuestOutline;
import com.rs.game.model.entity.player.Player;
import com.rs.plugin.annotations.PluginEventHandler;

import java.util.List;

@PluginEventHandler
public class EyesOfGlouphrie extends QuestOutline {

    @Override
    public List<String> getJournalLines(Player player, int stage) {
        return null;
    }

    @Override
    public void complete(Player player) {

    }
}
