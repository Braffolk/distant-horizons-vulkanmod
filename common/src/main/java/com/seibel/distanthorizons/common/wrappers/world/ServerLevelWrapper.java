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

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderRegister;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.messages.base.LevelInitMessage;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.world.EWorldEnvironment;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;

import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;

#if MC_VER <= MC_1_20_4
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif

import com.seibel.distanthorizons.core.logging.DhLogger;
import org.jetbrains.annotations.Nullable;

public class ServerLevelWrapper implements IServerLevelWrapper
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	/** 
	 * weak references are to prevent rare issues
	 * where, upon world closure, some levels aren't shutdown/removed properly
	 */
	private static final Map<ServerLevel, WeakReference<ServerLevelWrapper>> LEVEL_WRAPPER_REF_BY_SERVER_LEVEL = Collections.synchronizedMap(new WeakHashMap<>());
	
	private final ServerLevel level;
	private IDhLevel dhLevel;
	
	/** 
	 * this name is cached to prevent issues during shutdown where
	 * the server variables needed may no longer be available.
	 */
	private final String KeyedLevelDimensionName;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public static ServerLevelWrapper getWrapper(ServerLevel level) 
	{
		return LEVEL_WRAPPER_REF_BY_SERVER_LEVEL.compute(level, (newLevel, levelRef) ->
		{
			if (levelRef != null)
			{
				ServerLevelWrapper oldLevelWrapper = levelRef.get();
				if (oldLevelWrapper != null)
				{
					return levelRef;
				}
			}
			
			return new WeakReference<>(new ServerLevelWrapper(newLevel));
		}).get();
	}
	
	public ServerLevelWrapper(ServerLevel level) 
	{ 
		this.level = level;
		this.KeyedLevelDimensionName = this.createKeyedLevelDimensionName();
	}
	
	
	
	//==================//
	// instance methods //
	//==================//
	
	@Override
	public File getMcSaveFolder() 
	{ 
		#if MC_VER < MC_1_21_3
		return this.level.getChunkSource().getDataStorage().dataFolder;
		#else
		return this.level.getChunkSource().getDataStorage().dataFolder.toFile();
		#endif
	}
	
	@Override
	public String getKeyedLevelDimensionName() { return this.KeyedLevelDimensionName; }
	
	private String createKeyedLevelDimensionName()
	{
		String dimensionName = this.getDhIdentifier();
		
		if (Config.Server.sendLevelKeys.get())
		{
			String levelKeyPrefix = Config.Server.levelKeyPrefix.get();
			
			if (SharedApi.getEnvironment() == EWorldEnvironment.CLIENT_SERVER)
			{
				String cleanWorldFolderName = this.getWorldFolderName()
						.replaceAll("[^" + LevelInitMessage.ALLOWED_CHARS_REGEX + " ]", "")
						.replaceAll(" ", "_");
				
				levelKeyPrefix += (!levelKeyPrefix.isEmpty() ? "_" : "") + cleanWorldFolderName
						+ "_" + this.getHashedSeedEncoded();
			}
			
			if (levelKeyPrefix.isEmpty())
			{
				levelKeyPrefix = this.getHashedSeedEncoded();
			}
			
			String mainPart = "@" + dimensionName;
			
			return levelKeyPrefix.substring(0, Math.min(
					LevelInitMessage.MAX_LENGTH - mainPart.length(),
					levelKeyPrefix.length()
			)) + mainPart;
		}
		
		return dimensionName;
	}
	private String getWorldFolderName()
	{
		try
		{
			// We use the overworld since it's the only dimension that is stored in the server root folder
			
			#if MC_VER >= MC_1_21_3
			return this.level.getServer().getLevel(Level.OVERWORLD).getChunkSource().getDataStorage().dataFolder.getParent().getFileName().toString();
			#else // <= 1.21.3
			return this.level.getServer().getLevel(Level.OVERWORLD).getChunkSource().getDataStorage().dataFolder.getParentFile().getName();
			#endif
		}
		catch (Exception e)
		{
			LOGGER.warn("Unable to get world folder name. LODs may not load or save correctly. Error: ["+e.getMessage()+"].", e);
			return "unknown_world";
		}
	}
	
	
	@Override
	public DimensionTypeWrapper getDimensionType() 
	{
		#if MC_VER <= MC_1_21_10
		return DimensionTypeWrapper.getDimensionTypeWrapper(this.level.dimensionType());
		#else
		return DimensionTypeWrapper.getDimensionTypeWrapper(this.level.dimensionType(), this.getDimensionName());
		#endif
	}
	
	@Override
	public String getDimensionName()
	{
		#if MC_VER <= MC_1_21_10
		return this.level.dimension().location().toString();
		#else
		return this.level.dimension().identifier().toString();
		#endif
	}
	
	@Override
	public long getHashedSeed() { return this.level.getBiomeManager().biomeZoomSeed; }
	
	@Override
	public String getDhIdentifier() { return this.getDimensionName(); }
	
	@Override
	public EDhApiLevelType getLevelType() { return EDhApiLevelType.SERVER_LEVEL; }
	
	public ServerLevel getLevel() { return this.level; }
	
	@Override
	public boolean hasCeiling() { return this.level.dimensionType().hasCeiling(); }
	
	@Override
	public boolean hasSkyLight() { return this.level.dimensionType().hasSkyLight(); }
	
	@Override
	public int getMaxHeight() { return this.level.getHeight(); }
	
	@Override
	public int getMinHeight()
	{
        #if MC_VER < MC_1_17_1
        return 0;
        #elif MC_VER < MC_1_21_3
		return this.level.getMinBuildHeight();
        #else
		return this.level.getMinY();
        #endif
	}
	
	@Override
	public ServerLevel getWrappedMcObject() { return this.level; }
	
	@Override
	public void onUnload() { LEVEL_WRAPPER_REF_BY_SERVER_LEVEL.remove(this.level); }
	
	
	@Override
	public void setDhLevel(IDhLevel dhLevel) { this.dhLevel = dhLevel; }
	@Override
	@Nullable
	public IDhLevel getDhLevel() { return this.dhLevel; }
	
	@Override
	public IDhApiCustomRenderRegister getRenderRegister()
	{
		if (this.dhLevel == null)
		{
			return null;
		}
		
		return this.dhLevel.getGenericRenderer();
	}
	
	@Override
	public File getDhSaveFolder()
	{
		if (this.dhLevel == null)
		{
			return null;
		}
		
		return this.dhLevel.getSaveStructure().getSaveFolder(this);
	}
	
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public String toString() { return "Wrapped{" + this.level.toString() + "@" + this.getDhIdentifier() + "}"; }
	
}
