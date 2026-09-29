package com.rs.game.content.quests.murdermystery;

import com.rs.engine.quest.Quest;
import com.rs.game.model.entity.player.Player;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.handlers.ItemOnItemHandler;

@PluginEventHandler
public class MurderMysteryFingerprints {

    private static final int POT_OF_FLOUR = 1933;
    private static final int EMPTY_POT = 1931;

    private static final int CRIMINAL_DAGGER = 1813;
    private static final int CRIMINAL_DAGGER_DUSTED = 1814;
    private static final int PUNGENT_POT = 1812;

    private static final int ANNAS_NECKLACE = 1796;
    private static final int ANNAS_NECKLACE_DUSTED = 1797;
    private static final int BOBS_CUP = 1798;
    private static final int BOBS_CUP_DUSTED = 1799;
    private static final int CAROLS_BOTTLE = 1800;
    private static final int CAROLS_BOTTLE_DUSTED = 1801;
    private static final int DAVIDS_BOOK = 1802;
    private static final int DAVIDS_BOOK_DUSTED = 1803;
    private static final int ELIZABETHS_NEEDLE = 1804;
    private static final int ELIZABETHS_NEEDLE_DUSTED = 1805;
    private static final int FRANKS_POT = 1806;
    private static final int FRANKS_POT_DUSTED = 1807;

    private static final int FLYPAPER = 1811;
    private static final int KILLERS_PRINT = 1815;
    private static final int ANNAS_PRINT = 1816;
    private static final int BOBS_PRINT = 1817;
    private static final int CAROLS_PRINT = 1818;
    private static final int DAVIDS_PRINT = 1819;
    private static final int ELIZABETHS_PRINT = 1820;
    private static final int FRANKS_PRINT = 1821;
    private static final int UNKNOWN_PRINT = 1822;

    private static final int MURDERER_ANNA = 1;
    private static final int MURDERER_BOB = 2;
    private static final int MURDERER_CAROL = 3;
    private static final int MURDERER_DAVID = 4;
    private static final int MURDERER_ELIZABETH = 5;
    private static final int MURDERER_FRANK = 6;

    private static boolean questActive(Player player) {
        int stage = player.getQuestManager().getStage(Quest.MURDER_MYSTERY);
        return stage > 0 && stage < 7;
    }

    public static ItemOnItemHandler dustEvidence = new ItemOnItemHandler(
            POT_OF_FLOUR,
            new int[] {
                    CRIMINAL_DAGGER, PUNGENT_POT,
                    ANNAS_NECKLACE, BOBS_CUP, CAROLS_BOTTLE,
                    DAVIDS_BOOK, ELIZABETHS_NEEDLE, FRANKS_POT
            },
            e -> {
                Player player = e.getPlayer();
                if (!questActive(player))
                    return;

                if (e.usedWith(POT_OF_FLOUR, CRIMINAL_DAGGER)) {
                    dust(player, CRIMINAL_DAGGER, CRIMINAL_DAGGER_DUSTED,
                            "You sprinkle a small amount of flour on the murder weapon.",
                            "The murder weapon is now coated with a thin layer of flour.");
                    return;
                }

                if (e.usedWith(POT_OF_FLOUR, PUNGENT_POT)) {
                    player.getInventory().deleteItem(POT_OF_FLOUR, 1);
                    player.getInventory().addItem(EMPTY_POT, 1);
                    player.sendMessage("You sprinkle a small amount of flour on the strange smelling pot.");
                    player.sendMessage("The surface isn't shiny enough to take a fingerprint from.");
                    return;
                }

                if (e.usedWith(POT_OF_FLOUR, ANNAS_NECKLACE)) {
                    dust(player, ANNAS_NECKLACE, ANNAS_NECKLACE_DUSTED,
                            "You sprinkle the flour on Anna's necklace.",
                            "The necklace is now coated with a thin layer of flour.");
                    return;
                }

                if (e.usedWith(POT_OF_FLOUR, BOBS_CUP)) {
                    dust(player, BOBS_CUP, BOBS_CUP_DUSTED,
                            "You sprinkle the flour on Bob's cup.",
                            "The cup is now coated with a thin layer of flour.");
                    return;
                }

                if (e.usedWith(POT_OF_FLOUR, CAROLS_BOTTLE)) {
                    dust(player, CAROLS_BOTTLE, CAROLS_BOTTLE_DUSTED,
                            "You sprinkle the flour on Carol's bottle.",
                            "The bottle is now coated with a thin layer of flour.");
                    return;
                }

                if (e.usedWith(POT_OF_FLOUR, DAVIDS_BOOK)) {
                    dust(player, DAVIDS_BOOK, DAVIDS_BOOK_DUSTED,
                            "You sprinkle the flour on David's book.",
                            "The Book is now coated with a thin layer of flour.");
                    return;
                }

                if (e.usedWith(POT_OF_FLOUR, ELIZABETHS_NEEDLE)) {
                    dust(player, ELIZABETHS_NEEDLE, ELIZABETHS_NEEDLE_DUSTED,
                            "You sprinkle the flour on Elizabeth's needle.",
                            "The Needle is now coated with a thin layer of flour.");
                    return;
                }

                if (e.usedWith(POT_OF_FLOUR, FRANKS_POT)) {
                    dust(player, FRANKS_POT, FRANKS_POT_DUSTED,
                            "You sprinkle the flour on Frank's pot",
                            "The Pot is now coated with a thin layer of flour.");
                }
            }
    );

    public static ItemOnItemHandler liftFingerprints = new ItemOnItemHandler(
            FLYPAPER,
            new int[] {
                    CRIMINAL_DAGGER_DUSTED,
                    ANNAS_NECKLACE_DUSTED, BOBS_CUP_DUSTED,
                    CAROLS_BOTTLE_DUSTED, DAVIDS_BOOK_DUSTED,
                    ELIZABETHS_NEEDLE_DUSTED, FRANKS_POT_DUSTED
            },
            e -> {
                Player player = e.getPlayer();
                if (!questActive(player))
                    return;

                if (e.usedWith(FLYPAPER, CRIMINAL_DAGGER_DUSTED)) {
                    lift(player, CRIMINAL_DAGGER_DUSTED, CRIMINAL_DAGGER, UNKNOWN_PRINT,
                            "You use the flypaper on the floury dagger.",
                            "You have a clean impression of the murderer's finger prints.");
                    return;
                }

                if (e.usedWith(FLYPAPER, ANNAS_NECKLACE_DUSTED)) {
                    lift(player, ANNAS_NECKLACE_DUSTED, ANNAS_NECKLACE, ANNAS_PRINT,
                            "You use the flypaper on the flour covered necklace.",
                            "You have a clean impression of Anna's finger prints.");
                    return;
                }

                if (e.usedWith(FLYPAPER, BOBS_CUP_DUSTED)) {
                    lift(player, BOBS_CUP_DUSTED, BOBS_CUP, BOBS_PRINT,
                            "You use the flypaper on the flour covered cup.",
                            "You have a clean impression of Bob's finger prints.");
                    return;
                }

                if (e.usedWith(FLYPAPER, CAROLS_BOTTLE_DUSTED)) {
                    lift(player, CAROLS_BOTTLE_DUSTED, CAROLS_BOTTLE, CAROLS_PRINT,
                            "You use the flypaper on the flour covered bottle.",
                            "You have a clean impression of Carol's finger prints.");
                    return;
                }

                if (e.usedWith(FLYPAPER, DAVIDS_BOOK_DUSTED)) {
                    lift(player, DAVIDS_BOOK_DUSTED, DAVIDS_BOOK, DAVIDS_PRINT,
                            "You use the flypaper on the flour covered book.",
                            "You have a clean impression of David's finger prints.");
                    return;
                }

                if (e.usedWith(FLYPAPER, ELIZABETHS_NEEDLE_DUSTED)) {
                    lift(player, ELIZABETHS_NEEDLE_DUSTED, ELIZABETHS_NEEDLE, ELIZABETHS_PRINT,
                            "You use the flypaper on the flour covered needle.",
                            "You have a clean impression of Elizabeth's finger prints.");
                    return;
                }

                if (e.usedWith(FLYPAPER, FRANKS_POT_DUSTED)) {
                    lift(player, FRANKS_POT_DUSTED, FRANKS_POT, FRANKS_PRINT,
                            "You use the flypaper on the flour covered pot.",
                            "You have a clean impression of Frank's finger prints.");
                }
            }
    );

    public static ItemOnItemHandler compareFingerprints = new ItemOnItemHandler(
            UNKNOWN_PRINT,
            new int[] {
                    ANNAS_PRINT, BOBS_PRINT, CAROLS_PRINT,
                    DAVIDS_PRINT, ELIZABETHS_PRINT, FRANKS_PRINT
            },
            e -> {
                Player player = e.getPlayer();
                if (!questActive(player))
                    return;

                int printId;
                int murderer;
                String name;

                if (e.usedWith(UNKNOWN_PRINT, ANNAS_PRINT)) {
                    printId = ANNAS_PRINT;
                    murderer = MURDERER_ANNA;
                    name = "Anna";
                } else if (e.usedWith(UNKNOWN_PRINT, BOBS_PRINT)) {
                    printId = BOBS_PRINT;
                    murderer = MURDERER_BOB;
                    name = "Bob";
                } else if (e.usedWith(UNKNOWN_PRINT, CAROLS_PRINT)) {
                    printId = CAROLS_PRINT;
                    murderer = MURDERER_CAROL;
                    name = "Carol";
                } else if (e.usedWith(UNKNOWN_PRINT, DAVIDS_PRINT)) {
                    printId = DAVIDS_PRINT;
                    murderer = MURDERER_DAVID;
                    name = "David";
                } else if (e.usedWith(UNKNOWN_PRINT, ELIZABETHS_PRINT)) {
                    printId = ELIZABETHS_PRINT;
                    murderer = MURDERER_ELIZABETH;
                    name = "Elizabeth";
                } else if (e.usedWith(UNKNOWN_PRINT, FRANKS_PRINT)) {
                    printId = FRANKS_PRINT;
                    murderer = MURDERER_FRANK;
                    name = "Frank";
                } else {
                    return;
                }

                compare(player, printId, murderer, name);
            }
    );

    private static void dust(Player player, int original, int dusted, String firstMessage, String secondMessage) {
        player.getInventory().deleteItem(original, 1);
        player.getInventory().deleteItem(POT_OF_FLOUR, 1);
        player.getInventory().addItem(dusted, 1);
        player.getInventory().addItem(EMPTY_POT, 1);
        player.sendMessage(firstMessage);
        player.sendMessage(secondMessage);
    }

    private static void lift(Player player, int dusted, int original, int print, String firstMessage, String secondMessage) {
        player.getInventory().deleteItem(dusted, 1);
        player.getInventory().deleteItem(FLYPAPER, 1);
        player.getInventory().addItem(original, 1);
        player.getInventory().addItem(print, 1);
        player.sendMessage(firstMessage);
        player.sendMessage(secondMessage);
    }

    private static void compare(Player player, int namedPrint, int suspect, String name) {
        int murderer = MurderMysteryObjectsKt.ensureMurderer(player);

        if (murderer == suspect) {
            player.sendMessage("The finger prints are an exact match to " + name + "'s.");
            player.getInventory().deleteItem(UNKNOWN_PRINT, 1);
            player.getInventory().addItem(KILLERS_PRINT, 1);
        } else {
            player.sendMessage("They don't seem to be the same. I guess that clears " + name + " of the crime. You destroy the useless fingerprint.");
            player.getInventory().deleteItem(namedPrint, 1);
        }
    }
}
