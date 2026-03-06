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

package com.seibel.distanthorizons.common.wrappers.world;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.seibel.distanthorizons.core.wrapperInterfaces.world.IDimensionTypeWrapper;

import net.minecraft.world.level.dimension.DimensionType;

public class DimensionTypeWrapper implements IDimensionTypeWrapper
{
	private static final ConcurrentMap<String, DimensionTypeWrapper> DIMENSION_WRAPPER_BY_NAME = new ConcurrentHashMap<>();
	private final DimensionType dimensionType;
	
	private final String name;
	
	
	
	//=============//
	// Constructor //
	//=============//
	
	#if MC_VER <= MC_1_21_10
	public DimensionTypeWrapper(DimensionType dimensionType)
	#else
	public DimensionTypeWrapper(DimensionType dimensionType, String name)
	#endif
	{
		this.dimensionType = dimensionType; 
		
		#if MC_VER <= MC_1_21_10
		this.name = determineName(dimensionType);
		#else
		this.name = name;
		#endif
	}
	
	#if MC_VER <= MC_1_21_10
	public static DimensionTypeWrapper getDimensionTypeWrapper(DimensionType dimensionType)
	#else
	public static DimensionTypeWrapper getDimensionTypeWrapper(DimensionType dimensionType, String name)
	#endif
	{
		#if MC_VER <= MC_1_21_10
		String dimName = determineName(dimensionType);
		#else
		String dimName = name;
		#endif
		
		// check if the dimension has already been wrapped
		if (DIMENSION_WRAPPER_BY_NAME.containsKey(dimName) 
			&& DIMENSION_WRAPPER_BY_NAME.get(dimName) != null)
		{
			return DIMENSION_WRAPPER_BY_NAME.get(dimName);
		}
		
		
		// create the missing wrapper
		#if MC_VER <= MC_1_21_10
		DimensionTypeWrapper dimensionTypeWrapper = new DimensionTypeWrapper(dimensionType);
		#else
		DimensionTypeWrapper dimensionTypeWrapper = new DimensionTypeWrapper(dimensionType, dimName);
		#endif
		
		DIMENSION_WRAPPER_BY_NAME.put(dimName, dimensionTypeWrapper);
		return dimensionTypeWrapper;
	}
	private static String determineName(DimensionType dimensionType)
	{
		#if MC_VER <= MC_1_16_5
		// effectsLocation() is marked as client only, so using the backing field directly
		return dimensionType.effectsLocation.getPath();
		#elif MC_VER <= MC_1_21_10
		return dimensionType.effectsLocation().getPath();
		#else
		throw new UnsupportedOperationException("As of MC 1.21.11 the dimension type no longer stores it's name and must be determined from the level.");
		#endif
	}
	
	public static void clearMap() { DIMENSION_WRAPPER_BY_NAME.clear(); }
	
	
	
	//=================//
	// wrapper methods //
	//=================//
	
	@Override
	public String getName() { return this.name; }
	
	@Override
	public boolean hasCeiling() { return this.dimensionType.hasCeiling(); }
	
	@Override
	public boolean hasSkyLight() { return this.dimensionType.hasSkyLight(); }
	
	@Override
	public Object getWrappedMcObject() { return this.dimensionType; }
	
	@Override
	public boolean isTheEnd() { return this.getName().equalsIgnoreCase("the_end"); }
	
	@Override
	public double getCoordinateScale() { return this.dimensionType.coordinateScale(); }
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public boolean equals(Object obj)
	{
		if (obj.getClass() != DimensionTypeWrapper.class)
		{
			return false;
		}
		else
		{
			DimensionTypeWrapper other = (DimensionTypeWrapper) obj;
			return other.getName().equals(this.getName());
		}
	}
	
	
	
}
