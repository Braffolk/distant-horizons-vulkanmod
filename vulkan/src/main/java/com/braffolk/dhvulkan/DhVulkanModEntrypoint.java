package com.braffolk.dhvulkan;

import com.braffolk.dhvulkan.bridge.DhIntegration;
import com.braffolk.dhvulkan.bridge.DhVersionDetector;
import com.braffolk.dhvulkan.config.DhVulkanConfig;
import com.braffolk.dhvulkan.core.VulkanBackend;
import com.braffolk.dhvulkan.core.VulkanRenderEngine;
import com.braffolk.dhvulkan.dh24.Dh24Integration;
import com.braffolk.dhvulkan.compat.Compat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
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
        LOGGER.info("[DH-VulkanMod] Extension mod initializing...");

        DhVulkanConfig config = DhVulkanConfig.get();
        LOGGER.info("[DH-VulkanMod] Config loaded. vulkanRenderMode={}", config.vulkanRenderMode);

        // Register /dh-debug <0-6> client command
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("dh-debug")
                    .executes(ctx -> {
                        int mode = DhVulkanConfig.get().vulkanRenderMode;
                        String name = mode >= 0 && mode < MODE_NAMES.length ? MODE_NAMES[mode] : "Unknown";
                        ctx.getSource().sendFeedback(Component.literal(
                                "\u00a7b[DH-Vulkan]\u00a7r Render mode: " + mode + " (" + name + ")"));
                        return 1;
                    })
                    .then(ClientCommandManager.argument("mode", IntegerArgumentType.integer(0, 6))
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

        LOGGER.info("[DH-VulkanMod] VulkanMod detected. Vulkan rendering backend will be used.");

        // Create the shared Vulkan backend
        VulkanBackend backend = new VulkanRenderEngine();

        // Detect DH version and initialize the appropriate integration
        DhVersionDetector.DhVersion dhVersion = DhVersionDetector.detect();
        LOGGER.info("[DH-VulkanMod] Detected DH version: {}", dhVersion);

        switch (dhVersion) {
            case DH_3_0:
                // TODO: Phase 8b -- ApiDhIntegration
                LOGGER.warn("[DH-VulkanMod] DH 3.0 detected but API integration not yet implemented. Falling back to DH 2.4 path.");
                activeIntegration = new Dh24Integration();
                activeIntegration.initialize(backend);
                break;
            case DH_2_4:
            default:
                activeIntegration = new Dh24Integration();
                activeIntegration.initialize(backend);
                break;
        }

        LOGGER.info("[DH-VulkanMod] Using integration: {}", activeIntegration.getName());
    }

    /**
     * Called from MixinLodRenderer before the first render pass to wire the
     * delegate. Only applies to DH 2.4 path.
     */
    public static void wireIfNeeded() {
        if (activeIntegration instanceof Dh24Integration) {
            ((Dh24Integration) activeIntegration).wireIfNeeded();
        }
    }

    /**
     * Called from shared MixinLevelRenderer after MC terrain finishes rendering.
     * Dispatches deferredComposite to the active integration path.
     */
    public static void deferredComposite() {
        if (activeIntegration instanceof Dh24Integration) {
            ((Dh24Integration) activeIntegration).deferredComposite();
        }
        // TODO: Phase 8b — ApiDhIntegration.deferredComposite()
    }

    /** Get the active integration (for mixins that need it) */
    public static DhIntegration getActiveIntegration() {
        return activeIntegration;
    }
}
