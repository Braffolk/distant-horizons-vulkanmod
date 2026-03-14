package com.braffolk.dhvulkan.dh24;

import com.braffolk.dhvulkan.bridge.DhIntegration;
import com.braffolk.dhvulkan.core.VulkanBackend;
import com.braffolk.dhvulkan.dh24.duck.IVulkanLodRenderer;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * DH 2.4.x integration: wraps VulkanBackend in Dh24RenderDelegate
 * and wires it into LodRenderer via duck interface.
 */
public class Dh24Integration implements DhIntegration {

    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod");
    private Dh24RenderDelegate delegate;
    private boolean wired = false;

    @Override
    public void initialize(VulkanBackend backend) {
        this.delegate = new Dh24RenderDelegate(backend);
        LOGGER.info("[DH-VulkanMod] DH 2.4 integration initialized. Delegate ready for wiring.");
    }

    /**
     * Called from MixinLodRenderer on the first render frame to wire
     * the delegate into LodRenderer. Only runs once.
     */
    public void wireIfNeeded() {
        if (this.delegate == null || this.wired)
            return;

        try {
            IVulkanLodRenderer lodRenderer = (IVulkanLodRenderer) LodRenderer.INSTANCE;
            lodRenderer.dhvulkan$setVulkanDelegate(this.delegate);
            this.wired = true;
            LOGGER.info("[DH-VulkanMod] Dh24RenderDelegate wired into LodRenderer.");
        } catch (Exception e) {
            LOGGER.error("[DH-VulkanMod] Failed to wire delegate", e);
        }
    }

    public Dh24RenderDelegate getDelegate() {
        return this.delegate;
    }

    @Override
    public String getName() {
        return "DH 2.4 (mixin-based)";
    }
}
