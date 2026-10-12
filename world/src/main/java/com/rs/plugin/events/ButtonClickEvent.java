package com.rs.plugin.events;

import com.rs.game.model.entity.player.Player;
import com.rs.plugin.handlers.PluginHandler;

import java.util.HashMap;
import java.util.Map;

public class ButtonClickEvent implements PluginEvent {

	private static final Map<Object, PluginHandler<? extends PluginEvent>> HANDLERS = new HashMap<>();

	private final Player player;
	private final int interfaceId;
    private final int componentId;
    private final int slotId;
    private final int slotId2;
	private final int opNum;

	public ButtonClickEvent(Player player, int interfaceId, int componentId, int slotId, int slotId2, int opNum) {
		this.player = player;
		this.interfaceId = interfaceId;
		this.componentId = componentId;
		this.slotId = slotId;
		this.slotId2 = slotId2;
		this.opNum = opNum;
	}

	public Player getPlayer() {
		return player;
	}

	public int getInterfaceId() {
		return interfaceId;
	}

	public int getComponentId() {
		return componentId;
	}

	public int getComponent() {
		return interfaceId << 16 | componentId;
	}

	public int getSlotId() {
		return slotId;
	}

	public int getSlotId2() {
		return slotId2;
	}

	public int getOpNum() {
		return opNum;
	}

	@Override
	public PluginHandler<? extends PluginEvent> getMethod() {
		return HANDLERS.get(getInterfaceId());
	}

	public static void registerMethod(Class<?> eventType, PluginHandler<? extends PluginEvent> method) {
		for (Object key : method.keys()) {
			PluginHandler<? extends PluginEvent> old = HANDLERS.put(key, method);
			if (old != null)
				System.err.println("ERROR: Duplicate ButtonClick methods for key: " + key);
		}
	}

	public Player component1() {
		return player;
	}

	public int component2() {
		return interfaceId;
	}

	public int component3() {
		return componentId;
	}

	public int component4() {
		return slotId;
	}

	public int component5() {
		return slotId2;
	}

	public int component6() {
		return opNum;
	}
}
