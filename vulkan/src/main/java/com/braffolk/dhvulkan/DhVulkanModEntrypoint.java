package com.braffolk.dhvulkan;

import com.braffolk.dhvulkan.config.DhVulkanConfig;
import com.braffolk.dhvulkan.duck.IVulkanGLProxy;
import com.braffolk.dhvulkan.duck.IVulkanLodRenderer;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
import net.fabricmc.api.ClientModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main entrypoint for the DH-VulkanMod extension mod.
 *
 * Detects VulkanMod, creates the {@link VulkanRenderDelegate}, and wires it
 * into
 * DH's {@link LodRenderer} via the {@link IVulkanLodRenderer} duck interface.
 *
 * The delegate is wired lazily on first render frame because
 * LodRenderer.INSTANCE
 * may not exist during Fabric's onInitializeClient phase.
 */
public class DhVulkanModEntrypoint implements ClientModInitializer {

    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod");
    private static VulkanRenderDelegate pendingDelegate;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[DH-VulkanMod] Extension mod initializing...");

        // Load config (creates default if missing)
        DhVulkanConfig config = DhVulkanConfig.get();
        LOGGER.info("[DH-VulkanMod] Config loaded. vulkanDebugMode={}", config.vulkanDebugMode);

        if (!IVulkanGLProxy.isVulkanModActive()) {
            LOGGER.warn("[DH-VulkanMod] VulkanMod is NOT detected. Extension will be inactive.");
            return;
        }

        LOGGER.info("[DH-VulkanMod] VulkanMod detected. Vulkan rendering backend will be used.");

        // Create the delegate now, but defer wiring until LodRenderer.INSTANCE exists
        pendingDelegate = new VulkanRenderDelegate();
        LOGGER.info("[DH-VulkanMod] VulkanRenderDelegate created. Will wire on first render frame.");
    }

    /**
     * Called from MixinLodRenderer before the first render pass to wire the
     * delegate.
     * This ensures LodRenderer.INSTANCE exists.
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
