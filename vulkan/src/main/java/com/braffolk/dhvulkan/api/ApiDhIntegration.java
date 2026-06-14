package com.braffolk.dhvulkan.api;

import com.braffolk.dhvulkan.bridge.DhIntegration;
import com.braffolk.dhvulkan.core.VulkanBackend;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * DH 3.0 integration layer. Creates VkRenderApiDefinition and defers
 * binding into DH's SingletonInjector until DH's own delayed setup calls
 * setRenderingApiBindings() (intercepted by MixinDependencySetup).
 */
public class ApiDhIntegration implements DhIntegration {
    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod");

    private static ApiDhIntegration instance;

    private VkRenderApiDefinition renderApi;
    private VulkanBackend backend;

    @Override
    public void initialize(VulkanBackend backend) {
        this.backend = backend;
        this.renderApi = new VkRenderApiDefinition(backend);
        instance = this;
        LOGGER.debug("[DH-VulkanMod] DH 3.0 API integration created (renderer binding deferred to DH setup).");
    }

    @Override
    public VulkanBackend getBackend() {
        return this.backend;
    }

    @Override
    public String getName() {
        return "DH 3.0 API";
    }

    /** Called from MixinDependencySetup to bind renderers at the right time */
    public void bindRenderApi() {
        if (renderApi != null) {
            renderApi.bindRenderers();
            LOGGER.info("[DH-VulkanMod] Vulkan LOD renderer active for Distant Horizons 3.0.");
        }
    }

    public static ApiDhIntegration getInstance() {
        return instance;
    }
}
