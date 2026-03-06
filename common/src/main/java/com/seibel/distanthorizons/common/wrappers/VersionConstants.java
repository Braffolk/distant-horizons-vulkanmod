/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.common.wrappers;

import com.seibel.distanthorizons.core.wrapperInterfaces.IVersionConstants;

/**
 * @author James Seibel
 * @version 12-11-2021
 */
public class VersionConstants implements IVersionConstants
{
	public static final VersionConstants INSTANCE = new VersionConstants();
	
	
	private VersionConstants()
	{
		
	}
	
	
	@Override
	public String getMinecraftVersion()
	{
		// these values are hard-coded to prevent an issue with Forge (specifically 1.18.2) where
		// it can't load client classes when running as a dedicated server,
		// which was how we were dynamically accessing the MC version string
		
		#if MC_VER == MC_1_16_5
			return "1.16.5";
		
		#elif MC_VER == MC_1_17_1
			return "1.17.1";
		
		#elif MC_VER == MC_1_18_2
			return "1.18.2";
		
		#elif MC_VER == MC_1_19_2
			return "1.19.2";
		#elif MC_VER == MC_1_19_4
			return "1.19.4";
		
		#elif MC_VER == MC_1_20_1
			return "1.20.1";
		#elif MC_VER == MC_1_20_2
			return "1.20.2";
		#elif MC_VER == MC_1_20_4
			return "1.20.4";
		#elif MC_VER == MC_1_20_6
			return "1.20.6";
		
		#elif MC_VER == MC_1_21_1
			return "1.21.1";
		#elif MC_VER == MC_1_21_3
			return "1.21.3";
		#elif MC_VER == MC_1_21_4
			return "1.21.4";
		#elif MC_VER == MC_1_21_5
			return "1.21.5";
		#elif MC_VER == MC_1_21_6
			return "1.21.6";
		#elif MC_VER == MC_1_21_8
			return "1.21.8";
		
		#elif MC_VER == MC_1_21_9
			return "1.21.9";
		#elif MC_VER == MC_1_21_10
			return "1.21.10";
		#elif MC_VER == MC_1_21_11
			return "1.21.11";
		#else
			ERROR MC version constant missing
		#endif
		
	}
	
}