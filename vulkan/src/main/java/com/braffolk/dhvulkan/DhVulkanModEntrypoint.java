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
 */
public class DhVulkanModEntrypoint implements ClientModInitializer {

    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod");

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

        // VulkanRenderDelegate initialization is deferred to the first render frame
        // because VulkanMod's pipeline / device aren't ready during mod init.
        // We set up the delegate lazily via the LodRenderer mixin.
        IVulkanLodRenderer lodRenderer = (IVulkanLodRenderer) LodRenderer.INSTANCE;

        // Create and set the delegate. The delegate itself defers GPU init to
        // beginFrame().
        VulkanRenderDelegate delegate = new VulkanRenderDelegate();
        lodRenderer.dhvulkan$setVulkanDelegate(delegate);

        LOGGER.info("[DH-VulkanMod] VulkanRenderDelegate wired into LodRenderer. Ready.");
    }
}
