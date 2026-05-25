package com.braffolk.dhvulkan;

import com.braffolk.dhvulkan.bridge.DhIntegration;
import com.braffolk.dhvulkan.bridge.DhVersionDetector;
import com.braffolk.dhvulkan.config.DhVulkanConfig;
import com.braffolk.dhvulkan.core.VulkanBackend;
import com.braffolk.dhvulkan.core.VulkanRenderEngine;
import com.braffolk.dhvulkan.compat.Compat;
#if MC_VER < MC_26_1_2
import com.braffolk.dhvulkan.dh24.Dh24Integration;
#endif
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
#if MC_VER >= MC_26_1_2
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
#else
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
#endif
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main entrypoint for the DH-VulkanMod extension mod.
 * Detects DH version at runtime and initializes the appropriate integration.
 */
public class DhVulkanModEntrypoint implements ClientModInitializer {

    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod");

    private static final String[] MODE_NAMES = {
            "Normal", "DH Depth", "SSAO", "Fog Alpha", "Fog Color", "Normals", "MC Depth"
    };

    /** The active integration (dh24 or api), set during init */
    private static DhIntegration activeIntegration;

    @Override
    public void onInitializeClient() {
        LOGGER.debug("[DH-VulkanMod] Extension mod initializing...");

        DhVulkanConfig config = DhVulkanConfig.get();
        LOGGER.debug("[DH-VulkanMod] Config loaded. vulkanRenderMode={}", config.vulkanRenderMode);

        // Register /dh-debug <0-6> client command
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            #if MC_VER >= MC_26_1_2
            dispatcher.register(ClientCommands.literal("dh-debug")
            #else
            dispatcher.register(ClientCommandManager.literal("dh-debug")
            #endif
                    .executes(ctx -> {
                        int mode = DhVulkanConfig.get().vulkanRenderMode;
                        String name = mode >= 0 && mode < MODE_NAMES.length ? MODE_NAMES[mode] : "Unknown";
                        ctx.getSource().sendFeedback(Component.literal(
                                "\u00a7b[DH-Vulkan]\u00a7r Render mode: " + mode + " (" + name + ")"));
                        return 1;
                    })
                    #if MC_VER >= MC_26_1_2
                    .then(ClientCommands.argument("mode", IntegerArgumentType.integer(0, 6))
                    #else
                    .then(ClientCommandManager.argument("mode", IntegerArgumentType.integer(0, 6))
                    #endif
                            .executes(ctx -> {
                                int mode = IntegerArgumentType.getInteger(ctx, "mode");
                                DhVulkanConfig.get().vulkanRenderMode = mode;
                                DhVulkanConfig.get().save();
                                String name = mode >= 0 && mode < MODE_NAMES.length ? MODE_NAMES[mode] : "Unknown";
                                ctx.getSource().sendFeedback(Component.literal(
                                        "\u00a7b[DH-Vulkan]\u00a7r Render mode set to: " + mode + " (" + name + ")"));
                                return 1;
                            })));
        });

        if (!Compat.isVulkanModActive()) {
            LOGGER.warn("[DH-VulkanMod] VulkanMod is NOT detected. Extension will be inactive.");
            return;
        }

        LOGGER.info("[DH-VulkanMod] Loaded (VulkanMod + Distant Horizons).");

        // Create the shared Vulkan backend
        VulkanBackend backend = new VulkanRenderEngine();

        // Detect DH version and initialize the appropriate integration
        DhVersionDetector.DhVersion dhVersion = DhVersionDetector.detect();
        LOGGER.debug("[DH-VulkanMod] Detected DH version: {}", dhVersion);

        switch (dhVersion) {
            case DH_3_0:
                com.braffolk.dhvulkan.api.ApiDhIntegration existing =
                        com.braffolk.dhvulkan.api.ApiDhIntegration.getInstance();
                if (existing != null) {
                    activeIntegration = existing;
                    LOGGER.debug("[DH-VulkanMod] Reusing API integration (initialized during DH DependencySetup).");
                } else {
                    com.braffolk.dhvulkan.api.ApiDhIntegration apiIntegration =
                            new com.braffolk.dhvulkan.api.ApiDhIntegration();
                    apiIntegration.initialize(backend);
                    activeIntegration = apiIntegration;
                }
                break;
            #if MC_VER < MC_26_1_2
            case DH_2_4:
            default:
                activeIntegration = new Dh24Integration();
                activeIntegration.initialize(backend);
                break;
            #else
            default:
                LOGGER.error("[DH-VulkanMod] DH 3.0 is required for Minecraft 26.1+");
                break;
            #endif
        }

        LOGGER.debug("[DH-VulkanMod] Using integration: {}", activeIntegration.getName());
    }

    #if MC_VER < MC_26_1_2
    /**
     * Called from MixinLodRenderer before the first render pass to wire the
     * delegate. Only applies to DH 2.4 path.
     */
    public static void wireIfNeeded() {
        if (activeIntegration instanceof Dh24Integration) {
            ((Dh24Integration) activeIntegration).wireIfNeeded();
        }
    }
    #endif

    /** Get the active integration (for mixins that need it) */
    public static DhIntegration getActiveIntegration() {
        return activeIntegration;
    }

    /** Called from MixinDependencySetup when DH setup runs before this entrypoint. */
    public static void setActiveIntegration(DhIntegration integration) {
        activeIntegration = integration;
    }

    /**
     * Called from MixinLevelRenderer at renderLevel @RETURN to read MC's depth
     * buffer while the swapchain depth is in a known-good Vulkan image layout.
     * The result is cached and used by the next deferredComposite() call.
     */
    public static void readAndCacheMcDepth() {
        if (activeIntegration == null) return;
        VulkanBackend backend = activeIntegration.getBackend();
        if (backend != null) {
            backend.readAndCacheMcDepth();
        }
    }
}
