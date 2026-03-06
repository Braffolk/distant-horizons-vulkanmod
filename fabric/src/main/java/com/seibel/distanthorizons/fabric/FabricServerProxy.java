package com.seibel.distanthorizons.fabric;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkProcessingEvent;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.common.AbstractModInitializer;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.common.wrappers.misc.ServerPlayerWrapper;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ServerApi;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.common.AbstractPluginPacketSender;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.fabric.testing.TestChunkInputReplacerEvent;
import com.seibel.distanthorizons.fabric.testing.TestWorldGenBindingEvent;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.seibel.distanthorizons.core.logging.DhLogger;

#if MC_VER >= MC_1_20_6
import com.seibel.distanthorizons.common.CommonPacketPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
#else
import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
#endif

/**
 * This handles all events sent to the server,
 * and is the starting point for most of the mod.
 *
 * @author Ran
 * @author Tomlee
 * @version 5-11-2022
 */
public class FabricServerProxy implements AbstractModInitializer.IEventProxy
{
	private static final ServerApi SERVER_API = ServerApi.INSTANCE;
	@SuppressWarnings("unused")
	private static final AbstractPluginPacketSender PACKET_SENDER = (AbstractPluginPacketSender) SingletonInjector.INSTANCE.get(IPluginPacketSender.class);
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private final boolean isDedicatedServer;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public FabricServerProxy(boolean isDedicatedServer)
	{
		this.isDedicatedServer = isDedicatedServer;
	}
	
	
	
	private IClientLevelWrapper getClientLevelWrapper(ClientLevel level) { return ClientLevelWrapper.getWrapper(level); }
	private ServerLevelWrapper getServerLevelWrapper(ServerLevel level) { return ServerLevelWrapper.getWrapper(level); }
	private ServerPlayerWrapper getServerPlayerWrapper(ServerPlayer player) { return ServerPlayerWrapper.getWrapper(player); }
	
	/** Registers Fabric Events */
	@Override
	public void registerEvents()
	{
		LOGGER.info("Registering Fabric Server Events");
		
		/* Register the mod needed event callbacks */
		
		// can be enabled to test overrides/events without having to build a separate API project 
		if (false)
		{
			DhApiEventRegister.on(DhApiLevelLoadEvent.class, new TestWorldGenBindingEvent());
			DhApi.events.bind(DhApiChunkProcessingEvent.class, new TestChunkInputReplacerEvent());
		}
		
		
		// ServerWorldLoadEvent
		ServerLifecycleEvents.SERVER_STARTING.register((server) ->
		{
			ServerApi.INSTANCE.serverLoadEvent(this.isDedicatedServer);
		});
		// ServerWorldUnloadEvent
		ServerLifecycleEvents.SERVER_STOPPED.register((server) ->
		{
			ServerApi.INSTANCE.serverUnloadEvent();
		});
		
		// ServerLevelLoadEvent
		ServerWorldEvents.LOAD.register((server, level) ->
		{
			ServerApi.INSTANCE.serverLevelLoadEvent(this.getServerLevelWrapper(level));
		});
		// ServerLevelUnloadEvent
		ServerWorldEvents.UNLOAD.register((server, level) ->
		{
			ServerApi.INSTANCE.serverLevelUnloadEvent(this.getServerLevelWrapper(level));
		});
		
		// ServerChunkLoadEvent
		ServerChunkEvents.CHUNK_LOAD.register((server, chunk) ->
		{
			ILevelWrapper level = this.getServerLevelWrapper((ServerLevel) chunk.getLevel());
			ServerApi.INSTANCE.serverChunkLoadEvent(
				new ChunkWrapper(chunk, level),
				level);
		});
		// ServerChunkSaveEvent - Done in MixinChunkMap
		
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
		{
			ServerApi.INSTANCE.serverPlayerJoinEvent(this.getServerPlayerWrapper(handler.player));
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
		{
			ServerApi.INSTANCE.serverPlayerDisconnectEvent(this.getServerPlayerWrapper(handler.player));
		});
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, originLevel, destinationLevel) ->
		{
			ServerApi.INSTANCE.serverPlayerLevelChangeEvent(
				this.getServerPlayerWrapper(player),
				this.getServerLevelWrapper(originLevel),
				this.getServerLevelWrapper(destinationLevel)
			);
		});
		
		#if MC_VER >= MC_1_20_6
		PayloadTypeRegistry.playC2S().register(CommonPacketPayload.TYPE, new CommonPacketPayload.Codec());
		if (this.isDedicatedServer)
		{
			PayloadTypeRegistry.playS2C().register(CommonPacketPayload.TYPE, new CommonPacketPayload.Codec());
		}
		
		ServerPlayNetworking.registerGlobalReceiver(CommonPacketPayload.TYPE, (payload, context) ->
		{
			if (payload.message() == null)
			{
				return;
			}
			ServerApi.INSTANCE.pluginMessageReceived(ServerPlayerWrapper.getWrapper(context.player()), payload.message());
		});
		#else
		ServerPlayNetworking.registerGlobalReceiver(AbstractPluginPacketSender.WRAPPER_PACKET_RESOURCE, (server, serverPlayer, handler, buffer, packetSender) ->
		{
			AbstractNetworkMessage message = PACKET_SENDER.decodeMessage(buffer);
			if (message != null)
			{
				ServerApi.INSTANCE.pluginMessageReceived(ServerPlayerWrapper.getWrapper(serverPlayer), message);
			}
		});
		#endif
	}
	
}
