package com.rs.tools;

import world.gregs.voidps.cache.Cache;
import org.darkan.core.model.ObjectShape;
import com.rs.game.World;
import com.rs.game.model.gameobject.GameObject;
import org.darkan.core.map.MapXteas;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MapSearcher {

    public static void main(String[] args) throws IOException, InterruptedException {
		System.out.println("MapSearcher not yet migrated to modern cache API.");
	}

	public static void removeAllObjects(int id) {
		for (GameObject obj : getObjectsById(id))
			World.removeObject(obj);
	}

	public static List<GameObject> getObjectsById(int id) {
		return new ArrayList<>();
	}

}
