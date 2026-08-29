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

import com.rs.game.model.entity.player.Player;

import java.util.Arrays;
import java.util.Objects;

public class MachineInformation {

	public int operatingSystem;
	public boolean x64os;
	public int osVendor;
	public int javaVersion;
	public int javaSubBuild;
	public int javaBuild;
	public int javaUpdate;
	public int ram;
	public transient String aString8157;
	public transient String aString8160;
	public transient String aString8159;
	public transient String aString8153;
	public int[] rawCPUInformationData = new int[3];
	public transient boolean idk;
	public int maxMem;
	public int processors;
	public int cpuCores;
	public int cpuClock;
	public String cpuType;
	public int rawCPUInformation2;
	public int rawCPUInformation;
	public String cpuData;
	public int dxDriverMonth;
	public int dxDriverYear;

	public static MachineInformation from(org.darkan.core.model.MachineInformation core) {
		MachineInformation info = new MachineInformation();
		info.operatingSystem = core.getOperatingSystem();
		info.x64os = core.getX64os();
		info.osVendor = core.getOsVendor();
		info.javaVersion = core.getJavaVendor();
		info.javaBuild = core.getJavaBuild();
		info.javaSubBuild = core.getJavaSubBuild();
		info.javaUpdate = core.getJavaUpdate();
		info.ram = core.getRam();
		info.aString8157 = core.getGpuModel();
		info.aString8159 = core.getDwDirectXVersion();
		info.maxMem = core.getMaxMem();
		info.processors = core.getProcessors();
		info.cpuCores = core.getCpuCores();
		info.cpuClock = core.getCpuClock();
		info.cpuType = core.getCpuType();
		info.cpuData = core.getCpuData();
		info.rawCPUInformationData = core.getCpuFeatureFlags().clone();
		info.rawCPUInformation2 = core.getCpuModelInfo();
		info.dxDriverMonth = core.getDxDriverMonth();
		info.dxDriverYear = core.getDxDriverYear();
		return info;
	}

	@Override
	public int hashCode() {
		int result = Objects.hash(operatingSystem, x64os, osVendor, javaVersion, javaSubBuild, javaBuild, javaUpdate, ram, aString8157, aString8160, aString8159, aString8153, idk, maxMem, processors, cpuCores, cpuClock, cpuType, rawCPUInformation2, rawCPUInformation, cpuData, dxDriverMonth, dxDriverYear);
		result = 31 * result + Arrays.hashCode(rawCPUInformationData);
		return result;
	}

	public String getVersion() {
		return javaBuild + "." + javaVersion + "." + javaSubBuild;
	}

	public void sendSuggestions(Player player) {
		String suggestion = null;
		String title = null;
		if (javaBuild < 6) {
			title = "Client Issues";
			suggestion = "You seem to be using java version: " + getVersion() + ".<br>You should update to jre6.";
		}/*
		 * else if(javaVersionBuild != 0 || javaVersionBuild2 < 31) { title =
		 * "Outdated Java Version"; suggestion =
		 * "Your java seems outdated: "+getVersion
		 * ()+".<br>You should update your to 6.0.31.";
		 *//*
		 * }else if (hasApplet && ((availableProcessors <= (x64Arch ? 2 :
		 * 1)) || ram <= (x64Arch ? 1024 : 512) || cpuClockFrequency <=
		 * 1500)) { title = "Weak Specs"; suggestion =
		 * "Your computer seems to have weak specs. You'd better download desktop client for better perfomance."
		 * ; }
		 */
		if (title != null) {
			player.getInterfaceManager().sendInterface(405);
			player.getPackets().setIFText(405, 16, title);
			player.getPackets().setIFText(405, 17, suggestion);
		}
	}

}
