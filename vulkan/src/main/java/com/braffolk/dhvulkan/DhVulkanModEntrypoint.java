package com.braffolk.dhvulkan;

import com.braffolk.dhvulkan.config.DhVulkanConfig;
import com.braffolk.dhvulkan.duck.IVulkanGLProxy;
import com.braffolk.dhvulkan.duck.IVulkanLodRenderer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main entrypoint for the DH-VulkanMod extension mod.
 */
public class DhVulkanModEntrypoint implements ClientModInitializer {

    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod");
    private static VulkanRenderDelegate pendingDelegate;

    private static final String[] MODE_NAMES = {
            "Normal", "DH Depth", "SSAO", "Fog Alpha", "Fog Color", "Normals", "MC Depth"
    };

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

        if (!IVulkanGLProxy.isVulkanModActive()) {
            LOGGER.warn("[DH-VulkanMod] VulkanMod is NOT detected. Extension will be inactive.");
            return;
        }

        LOGGER.info("[DH-VulkanMod] VulkanMod detected. Vulkan rendering backend will be used.");
        pendingDelegate = new VulkanRenderDelegate();
        LOGGER.info("[DH-VulkanMod] VulkanRenderDelegate created. Will wire on first render frame.");
    }

    /**
     * Called from MixinLodRenderer before the first render pass to wire the
     * delegate.
     */
    public static void wireIfNeeded() {
        if (pendingDelegate == null)
            return;

        try {
            IVulkanLodRenderer lodRenderer = (IVulkanLodRenderer) LodRenderer.INSTANCE;
            lodRenderer.dhvulkan$setVulkanDelegate(pendingDelegate);
            LOGGER.info("[DH-VulkanMod] VulkanRenderDelegate wired into LodRenderer. Ready.");
            pendingDelegate = null;
        } catch (Exception e) {
            LOGGER.error("[DH-VulkanMod] Failed to wire delegate", e);
        }
    }
}
