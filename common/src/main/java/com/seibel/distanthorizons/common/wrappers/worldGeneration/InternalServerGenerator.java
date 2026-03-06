package com.seibel.distanthorizons.common.wrappers.worldGeneration;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.params.GlobalWorldGenParams;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkUpdateQueueManager;
import com.seibel.distanthorizons.core.api.internal.chunkUpdating.WorldChunkUpdateManager;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.generation.DhLightingEngine;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.util.ExceptionUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.TimerUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IC2meAccessor;
import com.seibel.distanthorizons.coreapi.ModInfo;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

#if MC_VER <= MC_1_20_4
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

public class InternalServerGenerator
{
	public static final DhLogger LOGGER = new DhLoggerBuilder()
			.name("LOD World Gen - Internal Server")
			.fileLevelConfig(Config.Common.Logging.logWorldGenEventToFile)
			.build();
	
	public static final DhLogger CHUNK_LOAD_LOGGER = new DhLoggerBuilder()
			.name("LOD Chunk Loading")
			.fileLevelConfig(Config.Common.Logging.logWorldGenChunkLoadEventToFile)
			.build();
	
	private static final IC2meAccessor C2ME_ACCESSOR = ModAccessorInjector.INSTANCE.get(IC2meAccessor.class);
	
	/**
	 * Used to revert the ignore logic in {@link SharedApi} so
	 * that a given chunk pos can be handled again.
	 * A timer is used so we don't have to inject into MC's code and it works sell enough
	 * most of the time.
	 * If a chunk does get through due the timeout not being long enough that isn't the end of the world.
	 */
	private static final int MS_TO_IGNORE_CHUNK_AFTER_COMPLETION = 5_000;
	
	#if MC_VER < MC_1_21_5
	private static final TicketType<ChunkPos> DH_SERVER_GEN_TICKET = TicketType.create("dh_server_gen_ticket", Comparator.comparingLong(ChunkPos::toLong));
	#elif MC_VER < MC_1_21_9
	private static final TicketType DH_SERVER_GEN_TICKET = new TicketType(/* timeout, 0 = disabled*/0L, /* persist */ false, TicketType.TicketUse.LOADING);
	#else
	private static final TicketType DH_SERVER_GEN_TICKET = new TicketType(/* timeout, 0 = disabled*/0L, /* flags */TicketType.FLAG_LOADING);
	#endif
	
	private static boolean c2meMissingWarningLogged = false;
	
	
	private final GlobalWorldGenParams params;
	private final IDhServerLevel dhServerLevel;
	private final Timer chunkSaveIgnoreTimer = TimerUtil.CreateTimer("ChunkSaveIgnoreTimer");
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public InternalServerGenerator(GlobalWorldGenParams params, IDhServerLevel dhServerLevel)
	{
		this.params = params;
		this.dhServerLevel = dhServerLevel;
	}
	
	
	
	//============//
	// generation //
	//============//
	
	public void generateChunksViaInternalServer(GenerationEvent genEvent)
	{
		this.runValidation();
		
		try
		{
			//=====================//
			// create gen requests //
			//=====================//
			
			ArrayList<CompletableFuture<ChunkAccess>> getChunkFutureList = new ArrayList<>();
			{
				Iterator<ChunkPos> chunkPosIterator = ChunkPosGenStream.getIterator(genEvent.minPos.getX(), genEvent.minPos.getZ(), genEvent.widthInChunks, 0);
				while (chunkPosIterator.hasNext())
				{
					ChunkPos chunkPos = chunkPosIterator.next();
					
					CompletableFuture<ChunkAccess> requestChunkFuture =
						this.requestChunkFromServerAsync(chunkPos)
							// log errors if necessary
							.whenCompleteAsync(
								(chunk, throwable) ->
								{
									// unwrap the CompletionException if necessary
									Throwable actualThrowable = throwable;
									while (actualThrowable instanceof CompletionException)
									{
										actualThrowable = actualThrowable.getCause();
									}
									
									if (actualThrowable != null)
									{
										// ignore expected shutdown exceptions
										boolean isShutdownException = 
											ExceptionUtil.isShutdownException(actualThrowable)
											|| actualThrowable.getMessage().contains("Unloaded chunk"); 
										if (!isShutdownException)
										{
											CHUNK_LOAD_LOGGER.warn("DistantHorizons: Couldn't load chunk [" + chunkPos + "] from server, error: [" + actualThrowable.getMessage() + "].", actualThrowable);
										}
									}
								});
					
					getChunkFutureList.add(requestChunkFuture);
				}
			}
			
			
			
			//==============================//
			// wait for generation requests //
			//==============================//
			
			// Join-ing each thread will prevent DH from working on anything else
			// but will also prevent over-queuing world gen tasks.
			// If C2ME is present the CPU will still be well utilized.
			
			ArrayList<IChunkWrapper> chunkWrappers = new ArrayList<>();
			for (int i = 0; i < getChunkFutureList.size(); i++)
			{
				CompletableFuture<ChunkAccess> getChunkFuture = getChunkFutureList.get(i);
				ChunkAccess chunk = getChunkFuture.join();
				if (chunk != null)
				{
					ChunkWrapper chunkWrapper = new ChunkWrapper(chunk, this.dhServerLevel.getLevelWrapper());
					chunkWrapper.createDhHeightMaps();
					chunkWrappers.add(chunkWrapper);
				}
			}
			
			
			
			//==========================//
			// process generated chunks //
			//==========================//
			
			int maxSkyLight = this.dhServerLevel.getServerLevelWrapper().hasSkyLight() ? LodUtil.MAX_MC_LIGHT : LodUtil.MIN_MC_LIGHT;
			for (int i = 0; i < chunkWrappers.size(); i++)
			{
				ChunkWrapper chunkWrapper = (ChunkWrapper)chunkWrappers.get(i);
				
				// pre-generated chunks should have lighting but new ones won't
				if (!chunkWrapper.isDhBlockLightingCorrect())
				{
					DhLightingEngine.INSTANCE.bakeChunkBlockLighting(chunkWrapper, chunkWrappers, maxSkyLight);
				}
				
				this.dhServerLevel.updateBeaconBeamsForChunk(chunkWrapper, chunkWrappers);
				genEvent.resultConsumer.accept(chunkWrapper);
			}
		}
		finally
		{
			// release all chunks from the server to prevent out of memory issues
			Iterator<ChunkPos> chunkPosIterator = ChunkPosGenStream.getIterator(genEvent.minPos.getX(), genEvent.minPos.getZ(), genEvent.widthInChunks, 0);
			while (chunkPosIterator.hasNext())
			{
				ChunkPos chunkPos = chunkPosIterator.next();
				this.releaseChunkFromServer(this.params.mcServerLevel, this.params.dhServerLevel, chunkPos);
			}
		}
	}
	private void runValidation()
	{
		// DH thread check
		if (!DhApi.isDhThread()
			&& ModInfo.IS_DEV_BUILD)
		{
			throw new IllegalStateException("Internal server generation should be called from one of DH's world gen thread. Current thread: ["+Thread.currentThread().getName()+"]");
		}
		
		
		// C2ME present?
		if (C2ME_ACCESSOR == null
			&& !c2meMissingWarningLogged)
		{
			c2meMissingWarningLogged = true;
			
			String c2meWarning = "C2ME missing, \n" +
				"low CPU usage and slow world gen speeds expected. \n" +
				"DH is set to use MC's internal server for world gen \n" +
				"this mode is less efficient unless a mod like C2ME is present."
				;
			
			if (Config.Common.Logging.Warning.showSlowWorldGenSettingWarnings.get())
			{
				String message =
					MinecraftTextFormat.ORANGE + "Distant Horizons: slow world gen." + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
						c2meWarning;
				ClientApi.INSTANCE.showChatMessageNextFrame(message);
			}
			
			LOGGER.warn(c2meWarning);
		}
	}
	private CompletableFuture<ChunkAccess> requestChunkFromServerAsync(ChunkPos chunkPos)
	{
		return CompletableFuture.supplyAsync(() ->
		{
			ServerLevel level = this.params.mcServerLevel;
			
			// ignore chunk update events for this position
			ChunkUpdateQueueManager updateManager = WorldChunkUpdateManager.INSTANCE.getByLevelWrapper(this.params.dhServerLevel.getServerLevelWrapper());
			if (updateManager != null)
			{
				updateManager.addPosToIgnore(new DhChunkPos(chunkPos.x, chunkPos.z));
			}
			
			#if MC_VER < MC_1_21_5
			int chunkLevel = 33; // 33 is equivalent to FULL Chunk
			level.getChunkSource().distanceManager.addTicket(DH_SERVER_GEN_TICKET, chunkPos, chunkLevel, chunkPos);
			#else
			level.getChunkSource().addTicketWithRadius(DH_SERVER_GEN_TICKET, chunkPos, 0);
			#endif
			
			// probably not the most optimal to run updates here, but fast enough
			level.getChunkSource().distanceManager.runAllUpdates(level.getChunkSource().chunkMap);
			
			ChunkHolder chunkHolder = level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(chunkPos.toLong());
			if (chunkHolder == null)
			{
				throw new IllegalStateException("No chunk chunkHolder for pos ["+chunkPos+"] after ticket has been added.");
			}
			
			#if MC_VER <= MC_1_20_4
			return chunkHolder.getOrScheduleFuture(ChunkStatus.FEATURES, level.getChunkSource().chunkMap)
					.thenApply(result -> result.left().orElseThrow(() -> new RuntimeException(result.right().get().toString()))); // can throw if the server is shutting down
			#elif MC_VER <= MC_1_20_6
			return chunkHolder.getOrScheduleFuture(ChunkStatus.FEATURES, level.getChunkSource().chunkMap)
					.thenApply(result -> result.orElseThrow(() -> new RuntimeException(result.toString()))); // can throw if the server is shutting down
			#else
			return chunkHolder.scheduleChunkGenerationTask(ChunkStatus.FEATURES, level.getChunkSource().chunkMap)
					.thenApply(result -> result.orElseThrow(() -> new RuntimeException(result.getError()))); // can throw if the server is shutting down
			#endif
			
		}, this.params.mcServerLevel.getChunkSource().chunkMap.mainThreadExecutor)
		.thenCompose(Function.identity());
	}
	/**
	 * mitigates out of memory issues in the vanilla chunk system. <br>
	 * See: https://github.com/pop4959/Chunky/pull/383
	 */
	private void releaseChunkFromServer(ServerLevel level, IDhServerLevel dhLevel, ChunkPos chunkPos)
	{
		level.getChunkSource().chunkMap.mainThreadExecutor.execute(() ->
		{
			try
			{
				#if MC_VER < MC_1_21_5
				int chunkLevel = 33; // 33 is equivalent to FULL Chunk
				level.getChunkSource().distanceManager.removeTicket(DH_SERVER_GEN_TICKET, chunkPos, chunkLevel, chunkPos);
				#else
				level.getChunkSource().removeTicketWithRadius(DH_SERVER_GEN_TICKET, chunkPos, 0);
				#endif
				
				level.getChunkSource().chunkMap.tick(() -> false);
				
				#if MC_VER > MC_1_16_5
				level.entityManager.tick();
				#endif
				
				
				// give MC a few seconds to save the chunk before
				// we can process update events there again
				this.chunkSaveIgnoreTimer.schedule(new TimerTask()
				{
					@Override
					public void run() 
					{
						ChunkUpdateQueueManager updateManager = WorldChunkUpdateManager.INSTANCE.getByLevelWrapper(dhLevel.getServerLevelWrapper());
						if (updateManager != null)
						{
							updateManager.addPosToIgnore(new DhChunkPos(chunkPos.x, chunkPos.z));
						}
						else
						{
							// shouldn't happen, but just in case
							LOGGER.warn("Unable to find chunk update manager for server level ["+dhLevel+"], chunk updates may fail.");
						}
					}
				}, MS_TO_IGNORE_CHUNK_AFTER_COMPLETION);
				
			}
			catch (Exception e)
			{
				LOGGER.warn("Failed to release chunk back to internal server. Error: ["+e.getMessage()+"]", e);
			}
		});
	}
	
	
	
}
