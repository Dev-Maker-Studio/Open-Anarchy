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
//  Copyright (C) 2021 Trenton Kress
//  This file is part of project: Darkan
//
package com.rs.utils;

import org.darkan.core.cache.BASDefinitions;
import org.darkan.core.cache.NPCDefinitions;
import org.darkan.core.cache.ObjectDefinitions;
import org.darkan.core.cache.SpotAnimDefinitions;
import org.darkan.core.cache.AnimationDefinitions;
import world.gregs.voidps.cache.definition.data.NPCDefinition;
import world.gregs.voidps.cache.definition.data.AnimationDefinition;
import world.gregs.voidps.cache.definition.data.GraphicDefinition;
import world.gregs.voidps.cache.definition.data.ObjectDefinition;
import world.gregs.voidps.cache.definition.data.BASDefinition;
import org.darkan.core.Utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ObjAnimList {

	private static final Set<Integer> USED = new HashSet<>();
	private static final List<Integer> OBJECT_ANIMS = new ArrayList<>();

	public static void main(String[] args) throws IOException {
		//Cache.init();

		init();

		System.out.println(OBJECT_ANIMS.size());
	}

	public static boolean inited() {
		return USED.size() > 0;
	}

	public static boolean isUsed(int anim) {
		return USED.contains(anim);
	}

	public static void init() {
		for (int npcId = 0;npcId < Utils.getNPCDefinitionsSize();npcId++) {
			NPCDefinition npcDef = NPCDefinitions.getDefs(npcId);
			if (npcDef.getRenderEmote() != -1)
				if (BASDefinitions.getDefs(npcDef.getRenderEmote()).getStandAnimation() != -1)
					if (AnimationDefinitions.getDefs(BASDefinitions.getDefs(npcDef.getRenderEmote()).getStandAnimation()).getFrames() != null) {
						int skeleton = AnimationDefinitions.getDefs(BASDefinitions.getDefs(npcDef.getRenderEmote()).getStandAnimation()).getFrames()[0];
						for (int i = 0; i < Utils.getAnimationDefinitionsSize(); i++) {
							AnimationDefinition check = AnimationDefinitions.getDefs(i);
							if ((check == null) || check.getFrames() == null || check.getFrames()[0] == -1)
								continue;
							if (check.getFrames()[0] == skeleton)
								USED.add(i);
						}
					}
		}

		for (int spotAnimId = 0;spotAnimId < Utils.getSpotAnimDefinitionsSize();spotAnimId++) {
			GraphicDefinition defs = SpotAnimDefinitions.getDefs(spotAnimId);
			if (defs.getAnimationId() != -1)
				USED.add(defs.getAnimationId());
		}

		for (int objectId = 0;objectId < Utils.getObjectDefinitionsSize();objectId++) {
			ObjectDefinition defs = ObjectDefinitions.getDefs(objectId);
			if (defs.getAnimations() != null)
				for (int animId : defs.getAnimations())
					USED.add(animId);
		}

		for (int i = 0; i < Utils.getAnimationDefinitionsSize(); i++)
			if (!USED.contains(i))
				OBJECT_ANIMS.add(i);
	}

}
