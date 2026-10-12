package com.rs.game.content.holidayevents.easter.easter22;

import com.rs.Settings;
import org.darkan.core.model.ObjectShape;
import org.darkan.core.map.pathfinder.Direction;
import com.rs.game.World;
import com.rs.game.content.holidayevents.easter.easter22.EggHunt.Spawns;
import com.rs.game.model.entity.npc.NPC;
import com.rs.game.model.entity.player.managers.EmotesManager.Emote;
import com.rs.game.model.gameobject.GameObject;
import com.rs.game.tasks.WorldTasks;
import world.gregs.voidps.type.Tile;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.annotations.ServerStartupEvent;
import com.rs.utils.Ticks;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@PluginEventHandler
public class Easter2022 {

    public static String STAGE_KEY = "easter2025";
    public static Boolean ENABLED = true;

    public static final int PERMANENT_EGGSTERMINATOR = 24146;
    public static final int XP_LAMP = 2528;
    public static final int LOYALTY_POINTS_AWARDED = 2500;
    public static final int EGG_ON_FACE_MASK = 24149;
    public static final int CHOCOLATE_EGG_ON_FACE_MASK = 24150;
    public static final int EASTER_TRACK = 273;
    public static final Emote EASTER_EMOTE = Emote.AROUND_THE_WORLD;

    public static final int EGGSTERMINATOR = 24145;
    public static final int CHOCOTREAT = 24148;
    public static final int EVIL_DRUMSTICK = 24147;

    public static final int RUBBLE = 69753;

    public static final int CHOCOCHICK = 15270;
    public static final int CHICK = 15271;
    public static final int EVIL_CHICKEN = 15262;
    public static final int CHOCATRICE = 15259;
    public static final int EVIL_CHICKEN_MEDIUM = 15263;
    public static final int CHOCATRICE_MEDIUM = 15260;
    public static final int EVIL_CHICKEN_LARGE = 15264;
    public static final int CHOCATRICE_LARGE = 15261;
    public static EggHunt event;

    static AtomicLong currentTime = new AtomicLong(Instant.now().getEpochSecond());
    static long startDate = 1744259476;
    static long endDate = 1746160276;

    

    @ServerStartupEvent
    public static void EasterEvent2022() {
        if (!ENABLED || currentTime.get() >= endDate)
            return;

        int ticksToStart = (Settings.getConfig().isDebug() || currentTime.get() >= startDate) ? 10 : Ticks.fromSeconds((int)(startDate - currentTime.get()));
        int ticksToEnd = Settings.getConfig().isDebug() ? 3000 : Ticks.fromSeconds((int)(endDate - currentTime.get()));
        
        WorldTasks.scheduleTimer(ticksToStart, (interval) -> {
        	if (interval >= ticksToEnd) {
                World.sendWorldMessage("<col=ff0000>News: The Easter event is now over! Thanks for participating!", false);
                ENABLED = false;
                return false;
            }
            if (interval == 0) {
                initEasterSpawns(ticksToEnd);
                event = new EggHunt();
            }
            return true;
        });
    }

    public static void initEasterSpawns(int ticksToEnd) {

        final NPC Chocatrice = new NPC(15259, Tile.of(3208, 3426, 0));
        final NPC EvilChicken = new NPC(15262, Tile.of(3216,3426,0));
        Chocatrice.faceDir(Direction.SOUTH);
        EvilChicken.faceDir(Direction.SOUTH);
        World.addNPC(Chocatrice);
        World.addNPC(EvilChicken);

        for (Spawns spawn : Spawns.values())
            World.spawnObject(spawn.getEgg());

        WorldTasks.schedule(ticksToEnd, () -> {
        	Chocatrice.finish();
        	EvilChicken.finish();
            for (EggHunt.Spawns spawn : EggHunt.Spawns.values())
            	World.removeObject(spawn.getEgg());
        });

        World.spawnObjectTemporary(new GameObject(70111, ObjectShape.SCENERY_INTERACT, 1, Tile.of(3210,3426,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70111, ObjectShape.SCENERY_INTERACT, 2, Tile.of(3210,3430,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70112, ObjectShape.SCENERY_INTERACT, 0, Tile.of(3211,3427,1)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70112, ObjectShape.SCENERY_INTERACT, 1, Tile.of(3211,3429,1)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70114, ObjectShape.SCENERY_INTERACT, 4, Tile.of(3213,3427,1)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70114, ObjectShape.SCENERY_INTERACT, 3, Tile.of(3213,3429,1)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70124, ObjectShape.WALL_STRAIGHT, 0, Tile.of(3212,3431,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70125, ObjectShape.WALL_STRAIGHT, 1, Tile.of(3210,3429,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70125, ObjectShape.WALL_STRAIGHT, 0, Tile.of(3212,3426,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70124, ObjectShape.WALL_STRAIGHT, 3, Tile.of(3210,3428,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70127, ObjectShape.WALL_STRAIGHT, 2, Tile.of(3213,3431,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70126, ObjectShape.WALL_STRAIGHT, 1, Tile.of(3215,3429,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70126, ObjectShape.WALL_STRAIGHT, 2, Tile.of(3213,3426,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70127, ObjectShape.WALL_STRAIGHT, 3, Tile.of(3215,3428,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70123, ObjectShape.SCENERY_INTERACT, 0, Tile.of(3211,3438,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70123, ObjectShape.SCENERY_INTERACT, 0, Tile.of(3214,3438,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70119, ObjectShape.SCENERY_INTERACT, 0, Tile.of(3224,3427,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70119, ObjectShape.SCENERY_INTERACT, 2, Tile.of(3224,3432,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70117, ObjectShape.SCENERY_INTERACT, 2, Tile.of(3224,3428,1)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70123, ObjectShape.GROUND_INTERACT, 1, Tile.of(3229,3428,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70116, ObjectShape.SCENERY_INTERACT, 1, Tile.of(3208,3410,1)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70116, ObjectShape.SCENERY_INTERACT, 0, Tile.of(3200,3427,1)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70119, ObjectShape.SCENERY_INTERACT, 0, Tile.of(3200,3426,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70123, ObjectShape.SCENERY_INTERACT, 3, Tile.of(3205,3431,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70122, ObjectShape.SCENERY_INTERACT, 2, Tile.of(3205,3433,1)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70121, ObjectShape.SCENERY_INTERACT, 2, Tile.of(3209,3416,1)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70123, ObjectShape.GROUND_INTERACT, 2, Tile.of(3209,3419,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(70123, ObjectShape.GROUND_INTERACT, 1, Tile.of(3213,3420,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(42651, ObjectShape.SCENERY_INTERACT, 0, Tile.of(3222,3435,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(42652, ObjectShape.SCENERY_INTERACT, 3, Tile.of(3224,3425,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(42650, ObjectShape.SCENERY_INTERACT, 3, Tile.of(3219,3421,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(42650, ObjectShape.SCENERY_INTERACT, 0, Tile.of(3223,3421,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(42650, ObjectShape.SCENERY_INTERACT, 2, Tile.of(3207,3420,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(42652, ObjectShape.SCENERY_INTERACT, 0, Tile.of(3206,3421,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(42651, ObjectShape.SCENERY_INTERACT, 0, Tile.of(3200,3442,0)), ticksToEnd);
        World.spawnObjectTemporary(new GameObject(42651, ObjectShape.SCENERY_INTERACT, 1, Tile.of(3199,3441,0)), ticksToEnd);
    }
}
