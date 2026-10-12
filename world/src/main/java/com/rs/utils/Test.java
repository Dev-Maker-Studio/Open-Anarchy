package com.rs.utils;

import com.google.gson.GsonBuilder;
import com.rs.Settings;
import world.gregs.voidps.cache.Cache;
import org.darkan.core.cache.ItemDefinitions;
import world.gregs.voidps.cache.definition.data.ItemDefinition;
import org.darkan.core.cache.VarBitDefinitions;
import org.darkan.core.cache.IComponentDefinitions;
import com.rs.game.model.entity.player.Controller;
import com.rs.game.model.item.ItemsContainer;
import org.darkan.core.JsonFileManager;
import org.darkan.core.model.Item;
import org.darkan.core.Utils;
import com.rs.utils.drop.Drop;
import com.rs.utils.drop.DropSet;
import com.rs.utils.drop.DropTable;
import com.rs.utils.json.ControllerAdapter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class Test {

	public static int HOURS_TO_SAMPLE = 300;
	
	public static void main(String[] args) throws IOException {
		JsonFileManager.setGSON(new GsonBuilder()
				.registerTypeAdapter(Controller.class, new ControllerAdapter())
				.disableHtmlEscaping()
				.setPrettyPrinting()
				.create());

		Settings.getConfig();
		Cache.init(Settings.getConfig().getCachePath());

		File file = new File("itemListMeshModifiers.txt");
		if (file.exists())
			file.delete();
		else
			file.createNewFile();
		BufferedWriter writer = new BufferedWriter(new FileWriter(file));
		writer.flush();
		for (int itemId = 0;itemId < Utils.getItemDefinitionsSize();itemId++) {
			ItemDefinition def = ItemDefinitions.getDefs(itemId);
			boolean colors = def.getOriginalColours() != null && def.getOriginalColours().length > 0 || def.getModifiedColours() != null && def.getModifiedColours().length > 0;
			boolean textures = def.getOriginalTextureColours() != null && def.getOriginalTextureColours().length > 0 || def.getModifiedTextureColours() != null && def.getModifiedTextureColours().length > 0;
			if (def.getEquipSlot() != -1 && (colors || textures)) {
				writer.write(itemId + " (" + def.getName() + "):");
				writer.newLine();
				if (colors) {
					writer.write("Original colors: " + Arrays.toString(def.getOriginalColours()));
					writer.write("Modified colors: " + Arrays.toString(def.getModifiedColours()));
					writer.newLine();
				}
				if (textures) {
					writer.write("Original textures: " + Arrays.toString(def.getOriginalTextureColours()));
					writer.write("Modified textures: " + Arrays.toString(def.getModifiedTextureColours()));
					writer.newLine();
				}
			}
		}
	}

}
