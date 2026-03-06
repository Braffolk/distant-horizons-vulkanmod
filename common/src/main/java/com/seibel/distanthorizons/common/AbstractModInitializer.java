package com.seibel.distanthorizons.common;

import com.mojang.brigadier.CommandDispatcher;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeDhInitEvent;
import com.seibel.distanthorizons.common.commands.CommandInitializer;
import com.seibel.distanthorizons.common.wrappers.DependencySetup;
import com.seibel.distanthorizons.common.wrappers.gui.DhDebugScreenEntry;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftServerWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.ConfigHandler;
import com.seibel.distanthorizons.core.config.eventHandlers.presets.ThreadPresetConfigEventHandler;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.jar.ModJarInfo;
import com.seibel.distanthorizons.core.jar.updater.SelfUpdater;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IModAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IModChecker;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import com.seibel.distanthorizons.coreapi.ModInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import com.seibel.distanthorizons.core.logging.DhLogger;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Base for all mod loader initializers 
 * and handles most setup. 
 */
public abstract class AbstractModInitializer
{
	protected static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private CommandInitializer commandInitializer;
	
	
	
	//==================//
	// abstract methods //
	//==================//
	
	protected abstract void createInitialSharedBindings();
	protected abstract void createInitialClientBindings();
	protected abstract IEventProxy createClientProxy();
	protected abstract IEventProxy createServerProxy(boolean isDedicated);
	protected abstract void initializeModCompat();
	
	protected abstract void subscribeRegisterCommandsEvent(Consumer<CommandDispatcher<CommandSourceStack>> eventHandler);
	
	protected abstract void subscribeClientStartedEvent(Runnable eventHandler);
	protected abstract void subscribeServerStartingEvent(Consumer<MinecraftServer> eventHandler);
	protected abstract void runDelayedSetup();
	
	
	
	//===================//
	// initialize events //
	//===================//
	
	public void onInitializeClient()
	{
		DependencySetup.createClientBindings();
		this.createInitialClientBindings();
		
		LOGGER.info("Initializing " + ModInfo.READABLE_NAME + " client, firing DhApiBeforeDhInitEvent...");
		ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeDhInitEvent.class, null);
		
		this.startup();
		this.logBuildInfo();
		
		this.createClientProxy().registerEvents();
		this.createServerProxy(false).registerEvents();
		
		this.initializeModCompat();
		
		// Client uses config for auto-updater, so it's initialized here instead of post-init stage
		this.initConfig();
		logModIncompatibilityWarnings(); // needs to be called after config loading
		
		LOGGER.info(ModInfo.READABLE_NAME + " client Initialized.");
		
		#if MC_VER < MC_1_21_9
		// debug screen rendering handled via a mixin
		#else
		DhDebugScreenEntry.register();
		#endif
		
		this.subscribeClientStartedEvent(this::postInit);
	}
	
	public void onInitializeServer()
	{
		DependencySetup.createServerBindings();
		
		LOGGER.info("Initializing " + ModInfo.READABLE_NAME + " server, firing DhApiBeforeDhInitEvent event...");
		ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeDhInitEvent.class, null);
		
		this.startup();
		this.logBuildInfo();
		
		// This prevents returning uninitialized Config values,
		// resulting from a circular reference mid-initialization in a static class
		// noinspection ResultOfMethodCallIgnored
		ThreadPresetConfigEventHandler.INSTANCE.toString();
		
		this.createServerProxy(true).registerEvents();
		
		this.initializeModCompat();
		
		LOGGER.info(ModInfo.READABLE_NAME + " server Initialized, adding event subscribers...");
		this.commandInitializer = new CommandInitializer();
		this.subscribeRegisterCommandsEvent(dispatcher -> { this.commandInitializer.initCommands(dispatcher); });
		
		this.subscribeServerStartingEvent(server -> 
		{
			MinecraftServerWrapper.INSTANCE.dedicatedServer = (DedicatedServer)server;
			
			this.initConfig();
			this.postInit();
			this.commandInitializer.onServerReady();
			
			this.checkForUpdates();
			
			LOGGER.info(ModInfo.READABLE_NAME + " server Initialized at " + server.getServerDirectory());
		});
	}
	
	
	
	//===========================//
	// inner initializer methods //
	//===========================//
	
	private void startup()
	{
		DependencySetup.createSharedBindings();
		SharedApi.init();
		this.createInitialSharedBindings();
	}
	
	private void logBuildInfo()
	{
		LOGGER.info(ModInfo.READABLE_NAME + ", Version: " + ModInfo.VERSION);
		
		// if the build is stable the branch/commit/etc shouldn't be needed
		if (ModInfo.IS_DEV_BUILD)
		{
			LOGGER.info("DH Branch: " + ModJarInfo.Git_Branch);
			LOGGER.info("DH Commit: " + ModJarInfo.Git_Commit);
			LOGGER.info("DH Jar Build Source: " + ModJarInfo.Build_Source);
		}
	}
	
	protected <T extends IModAccessor> void tryCreateModCompatAccessor(String modId, Class<? super T> accessorClass, Supplier<T> accessorConstructor)
	{
		IModChecker modChecker = SingletonInjector.INSTANCE.get(IModChecker.class);
		if (modChecker.isModLoaded(modId))
		{
			//noinspection unchecked
			ModAccessorInjector.INSTANCE.bind((Class<? extends IModAccessor>) accessorClass, accessorConstructor.get());
		}
		else
		{
			LOGGER.debug("Skipping mod compatibility accessor for: ["+modId+"]");
		}
	}
	
	private void initConfig()
	{
		ConfigHandler.tryRunFirstTimeSetup();
		Config.completeDelayedSetup();
		DhLogger.runDelayedConfigSetup();
	}
	
	private void checkForUpdates()
	{
		if (Config.Client.Advanced.AutoUpdater.enableAutoUpdater.get())
		{
			if (Config.Client.Advanced.AutoUpdater.enableSilentUpdates.get())
			{
				LOGGER.info("Silent updates are not allowed for dedicated servers; force disabling.");
				Config.Client.Advanced.AutoUpdater.enableSilentUpdates.set(false);
			}
			
			SelfUpdater.onStart();
		}
	}
	
	private void postInit()
	{
		LOGGER.info("Running Delayed setup...");
		this.runDelayedSetup();
		
		if (ConfigHandler.INSTANCE == null)
		{
			throw new IllegalStateException("Config was not initialized. Make sure to call LodCommonMain.initConfig() before calling this method.");
		}
		
		LOGGER.info("Delayed setup complete, firing DhApiAfterDhInitEvent event...");
		
		// should be fired after all delayed setup so singletons and config can be accessed
		ApiEventInjector.INSTANCE.fireAllEvents(DhApiAfterDhInitEvent.class, null);
	}
	
	
	
	//==================================//
	// mod partial compatibility checks //
	//==================================//
	
	/** 
	 * Some mods will work with a few tweaks
	 * or will partially work but have some known issues we can't solve.
	 * This method will log (and display to chat if enabled)
	 * these warnings and potential fixes.
	 */
	private static void logModIncompatibilityWarnings()
	{
		boolean showChatWarnings = Config.Common.Logging.Warning.showModCompatibilityWarningsOnStartup.get();
		IModChecker modChecker = SingletonInjector.INSTANCE.get(IModChecker.class);
		
		String startingString = "Partially Incompatible Distant Horizons mod detected: ";
		
		
		
		// Alex's caves
		if (modChecker.isModLoaded("alexscaves"))
		{
			// There've been a few reports about this mod breaking DH at a few different points in time
			// the fixes for said breakage changes depending on the version so unfortunately
			// all we can do is log a warning so the user can handle it.
			
			if (showChatWarnings)
			{
				String message =
					MinecraftTextFormat.ORANGE + "Distant Horizons: Alex's Cave detected." + MinecraftTextFormat.CLEAR_FORMATTING +
								"You may have to change Alex's config for DH to render. ";
				ClientApi.INSTANCE.showChatMessageNextFrame(message);
			}
			
			LOGGER.warn(startingString + "[Alex's Caves] may require some config changes in order to render Distant Horizons correctly.");
		}
		
		// William Wythers' Overhauled Overworld (WWOO)
		if (modChecker.isModLoaded("wwoo"))
		{
			// WWOO has a bug with it's world gen that can't be fixed by DH or WWOO
			// (at least that is what James learned after talking with WWOO)
			// WWOO will cause grid lines to appear in the world when DH generates the chunks
			// this might be due to how WWOO uses features for everything when generating
			// and said features don't always get to the edge of said chunks.
			
			String wwooWarning = "LODs generated by DH may have grid lines between sections. Disabling either WWOO or DH's distant generator will fix the problem.";
			
			if (showChatWarnings)
			{
				String message =
					MinecraftTextFormat.ORANGE + "Distant Horizons: WWOO detected." + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
								wwooWarning;
				ClientApi.INSTANCE.showChatMessageNextFrame(message);
			}
			
			LOGGER.warn(startingString + "[WWOO] "+ wwooWarning);
		}
		
		// Chunky
		boolean chunkyPresent = false;
		try
		{
			Class.forName("org.popcraft.chunky.api.ChunkyAPI");
			chunkyPresent = true;
		}
		catch (ClassNotFoundException ignore) { }
		
		if (chunkyPresent)
		{
			// Chunky can generate chunks faster than DH can process them,
			// causing holes in the LODs.
			// Generally it's better and faster to use DH's world generator.
			
			String chunkyWarning = "Chunky can cause DH LODs to have holes " +
					"since Chunky can generate chunks faster than DH can process them. \n" +
					"Using DH's distant generator instead of chunky or increasing DH's CPU thread count can resolve the issue.";
			
			if (showChatWarnings)
			{
				String message =
					MinecraftTextFormat.ORANGE + "Distant Horizons: Chunky detected." + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
								chunkyWarning;
				ClientApi.INSTANCE.showChatMessageNextFrame(message);
			}
			
			LOGGER.warn(startingString + "[Chunky] "+ chunkyWarning);
		}
		
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	public interface IEventProxy
	{
		void registerEvents();
	}
	
}
