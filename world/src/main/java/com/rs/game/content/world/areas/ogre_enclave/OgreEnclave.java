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
// Copyright (C) 2021 Trenton Kress
// This file is part of project: Darkan
//
package com.rs.game.content.world.areas.ogre_enclave;

import com.rs.engine.dialogue.Dialogue;
import com.rs.engine.dialogue.HeadE;
import com.rs.engine.quest.Quest;
import com.rs.game.model.entity.Hit;
import com.rs.game.model.entity.player.Player;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.handlers.NPCClickHandler;
import com.rs.plugin.handlers.ObjectClickHandler;
import world.gregs.voidps.type.Tile;

@PluginEventHandler
public class OgreEnclave {

    private static final int ENCLAVE_GUARD = 870;

    /*
     * Interactive forms of the six Watchtower ogre shamans.
     *
     * Their map-spawn wrapper NPCs are:
     *
     * 5175 -> 5176
     * 5180 -> 5181
     * 5183 -> 5184
     * 5186 -> 5187
     * 5189 -> 5190
     * 5192 -> 5193
     */
    private static final Object[] OGRE_SHAMANS = {
            5176,
            5181,
            5184,
            5187,
            5190,
            5193
    };

    /*
     * One of the shamans is used as the speaker during the
     * first-entry Watchtower scene.
     */
    private static final int CUTSCENE_OGRE_SHAMAN = 5176;

    private static final int ROCK_OF_DALGROTH = 2816;

    /*
     * WATCHTOWER_CRYSTAL_4.
     *
     * This is the crystal obtained from the Rock of Dalgroth.
     */
    private static final int GREEN_CRYSTAL = 2383;

    private static final int STAGE_FIND_SHAMAN_ENCLAVE = 8;
    private static final int STAGE_DISCOVER_SHAMANS = 9;
    private static final int STAGE_RECOVER_CRYSTALS = 12;

    private static final String SHAMAN_CAVE_GUARD_DISTRACTED =
            "watchtower_shaman_cave_guard_distracted";

    private static final String ENCLAVE_SCENE_SEEN =
            "watchtower_enclave_scene_seen";

    private static final String DALGROTH_CRYSTAL_OBTAINED =
            "watchtower_dalgroth_crystal_obtained";

    /*
     * Object 2804 outside Gu'Tanoth sends the player here.
     */
    private static final Tile ENCLAVE_ENTRANCE =
            Tile.of(2589, 9409, 0);

    /*
     * Object 32494 inside the cave sends the player back outside.
     */
    private static final Tile ENCLAVE_EXIT =
            Tile.of(2541, 3054, 0);

    /*
     * ----------------------------------------------------------------
     * SHAMAN CAVE ENTRANCE
     * ----------------------------------------------------------------
     */

    public static ObjectClickHandler handleogreenclaveentrance =
            new ObjectClickHandler(
                    new Object[]{2804},
                    e -> {
                        Player player = e.getPlayer();

                        /*
                         * Once Watchtower is complete there is no longer
                         * any quest restriction on entering the cave.
                         */
                        if (player.isQuestComplete(Quest.WATCHTOWER)) {
                            player.tele(ENCLAVE_ENTRANCE);
                            return;
                        }

                        int stage =
                                player.getQuestStage(Quest.WATCHTOWER);

                        /*
                         * The player has not progressed far enough through
                         * Watchtower to enter the shaman cave.
                         */
                        if (stage < STAGE_FIND_SHAMAN_ENCLAVE) {
                            player.sendMessage(
                                    "You do not meet the requirements for Watchtower."
                            );
                            return;
                        }

                        /*
                         * During stage 8 the enclave guard prevents entry
                         * until cave nightshade has been used on him.
                         */
                        if (stage == STAGE_FIND_SHAMAN_ENCLAVE &&
                                !player.getBool(SHAMAN_CAVE_GUARD_DISTRACTED)) {

                            player.startConversation(
                                    new Dialogue().addNPC(
                                            ENCLAVE_GUARD,
                                            HeadE.CALM_TALK,
                                            "No you don't!"
                                    )
                            );

                            return;
                        }

                        /*
                         * First successful quest entry.
                         *
                         * The nightshade has distracted the guard, so the
                         * player can finally enter the shaman cave.
                         */
                        if (stage == STAGE_FIND_SHAMAN_ENCLAVE) {

                            player.fadeScreen(() -> {
                                player.tele(ENCLAVE_ENTRANCE);

                                player.setQuestStage(
                                        Quest.WATCHTOWER,
                                        STAGE_DISCOVER_SHAMANS
                                );

                                if (!player.getBool(ENCLAVE_SCENE_SEEN)) {
                                    player.set(
                                            ENCLAVE_SCENE_SEEN,
                                            true
                                    );

                                    playEnclaveScene(player);
                                }
                            });

                            return;
                        }

                        /*
                         * The introductory scene has already happened.
                         * Later visits simply enter the cave normally.
                         */
                        player.tele(ENCLAVE_ENTRANCE);
                    }
            );

    /*
     * ----------------------------------------------------------------
     * FIRST ENTRY SCENE
     * ----------------------------------------------------------------
     *
     * Transcript:
     *
     * "Meanwhile, in the Shaman's enclave..."
     *
     * The blue dragon attacks the ogre shaman.
     *
     * Shaman:
     * "Hur, hut, hur. Stoopid dragon!"
     *
     * The shaman destroys the dragon.
     *
     * Shaman:
     * "Take dis!"
     * "Hur, hur, hur, hur!"
     *
     * We deliberately use the existing cave NPCs rather than spawning
     * duplicate shamans or dragons into the live cave.
     */
    private static void playEnclaveScene(Player player) {

        player.startConversation(
                new Dialogue()

                        .addSimple(
                                "Meanwhile, in the Shaman's enclave..."
                        )

                        .addNPC(
                                CUTSCENE_OGRE_SHAMAN,
                                HeadE.ANGRY,
                                "Hur, hut, hur. Stoopid dragon!"
                        )

                        .addNPC(
                                CUTSCENE_OGRE_SHAMAN,
                                HeadE.ANGRY,
                                "Take dis!"
                        )

                        .addNPC(
                                CUTSCENE_OGRE_SHAMAN,
                                HeadE.HAPPY_TALKING,
                                "Hur, hur, hur, hur!"
                        )
        );
    }

    /*
     * ----------------------------------------------------------------
     * SHAMAN CAVE EXIT
     * ----------------------------------------------------------------
     */

    public static ObjectClickHandler handleogreenclaveexit =
            new ObjectClickHandler(
                    new Object[]{32494},
                    e -> e.getPlayer().tele(ENCLAVE_EXIT)
            );

    /*
     * ----------------------------------------------------------------
     * TALKING TO AN OGRE SHAMAN
     * ----------------------------------------------------------------
     */

    public static NPCClickHandler talkToOgreShaman =
            new NPCClickHandler(
                    OGRE_SHAMANS,
                    new String[]{"Talk-to"},
                    e -> {
                        Player player = e.getPlayer();

                        if (player.isQuestComplete(Quest.WATCHTOWER))
                            return;

                        int stage =
                                player.getQuestStage(Quest.WATCHTOWER);

                        if (stage < STAGE_DISCOVER_SHAMANS ||
                                stage >= STAGE_RECOVER_CRYSTALS)
                            return;

                        player.startConversation(
                                new Dialogue()

                                        .addNPC(
                                                e.getNPC().getId(),
                                                HeadE.ANGRY,
                                                "Grr! How dare you talk to us?"
                                        )

                                        .addNPC(
                                                e.getNPC().getId(),
                                                HeadE.ANGRY,
                                                "Grr! How dare you talk to us?"
                                        )

                                        .addNext(() ->
                                                player.applyHit(
                                                        new Hit(
                                                                e.getNPC(),
                                                                200,
                                                                Hit.HitLook.MAGIC_DAMAGE
                                                        )
                                                )
                                        )
                        );
                    }
            );

    /*
     * ----------------------------------------------------------------
     * ATTACKING AN OGRE SHAMAN
     * ----------------------------------------------------------------
     */

    public static NPCClickHandler attackOgreShaman =
            new NPCClickHandler(
                    OGRE_SHAMANS,
                    new String[]{"Attack"},
                    e -> {
                        Player player = e.getPlayer();

                        if (player.isQuestComplete(Quest.WATCHTOWER))
                            return;

                        int stage =
                                player.getQuestStage(Quest.WATCHTOWER);

                        if (stage < STAGE_DISCOVER_SHAMANS ||
                                stage >= STAGE_RECOVER_CRYSTALS)
                            return;

                        player.sendMessage(
                                "You attempt to attack the shaman..."
                        );

                        player.startConversation(
                                new Dialogue()

                                        .addNPC(
                                                e.getNPC().getId(),
                                                HeadE.ANGRY,
                                                "You are no match for our magics!"
                                        )

                                        .addNPC(
                                                e.getNPC().getId(),
                                                HeadE.ANGRY,
                                                "You are no match for our magics!"
                                        )

                                        .addNext(() -> {

                                            player.sendMessage(
                                                    "A magic blast comes from the shaman."
                                            );

                                            player.sendMessage(
                                                    "You are badly injured by the blast."
                                            );

                                            int damage =
                                                    Math.max(
                                                            1,
                                                            player.getMaxHitpoints() / 2
                                                    );

                                            player.applyHit(
                                                    new Hit(
                                                            e.getNPC(),
                                                            damage,
                                                            Hit.HitLook.MAGIC_DAMAGE
                                                    )
                                            );
                                        })
                        );
                    }
            );

    /*
     * ----------------------------------------------------------------
     * ROCK OF DALGROTH
     * ----------------------------------------------------------------
     */

    public static ObjectClickHandler rockOfDalgroth =
            new ObjectClickHandler(
                    new Object[]{ROCK_OF_DALGROTH},
                    e -> {
                        Player player = e.getPlayer();

                        int stage =
                                player.getQuestStage(Quest.WATCHTOWER);

                        if (stage < STAGE_DISCOVER_SHAMANS)
                            return;

                        String option = e.getOption();

                        if (option.equalsIgnoreCase("Prospect")) {
                            prospectRock(player);
                            return;
                        }

                        if (option.equalsIgnoreCase("Mine")) {
                            mineRock(player);
                        }
                    }
            );

    /*
     * ----------------------------------------------------------------
     * PROSPECT ROCK OF DALGROTH
     * ----------------------------------------------------------------
     */

    private static void prospectRock(Player player) {

        player.sendMessage(
                "You examine the rock for ores..."
        );

        if (player.getBool(DALGROTH_CRYSTAL_OBTAINED)) {

            player.sendMessage(
                    "The rock has a gap in which a crystal used to sit."
            );

            return;
        }

        player.sendMessage(
                "The rock contains a crystal!"
        );
    }

    /*
     * ----------------------------------------------------------------
     * MINE ROCK OF DALGROTH
     * ----------------------------------------------------------------
     */

    private static void mineRock(Player player) {

        if (player.isQuestComplete(Quest.WATCHTOWER)) {
            return;
        }

        int stage =
                player.getQuestStage(Quest.WATCHTOWER);

        /*
         * The Rock of Dalgroth cannot be touched while the
         * shamans are still alive.
         */
        if (stage < STAGE_RECOVER_CRYSTALS) {

            player.startConversation(
                    new Dialogue().addPlayer(
                            HeadE.CONFUSED,
                            "I can't touch it... Perhaps it is linked with the shamans in some strange way?"
                    )
            );

            return;
        }

        /*
         * The player has already removed this crystal.
         */
        if (player.getBool(DALGROTH_CRYSTAL_OBTAINED)) {

            player.startConversation(
                    new Dialogue().addPlayer(
                            HeadE.CALM_TALK,
                            "I have already this crystal. There is no benefit to getting another."
                    )
            );

            return;
        }

        /*
         * Remove the crystal from the Rock of Dalgroth.
         */
        player.set(
                DALGROTH_CRYSTAL_OBTAINED,
                true
        );

        player.getInventory().addItemDrop(
                GREEN_CRYSTAL,
                1
        );

        player.sendMessage(
                "A crack appears in the rock and you prise a crystal out."
        );
    }
}