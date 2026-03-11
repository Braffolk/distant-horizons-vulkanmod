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

    @Override
    public void initialize(VulkanBackend backend) {
        this.delegate = new Dh24RenderDelegate(backend);
        LOGGER.info("[DH-VulkanMod] DH 2.4 integration initialized. Delegate ready for wiring.");
    }

    /**
     * Called from MixinLodRenderer on the first render frame to wire
     * the delegate into LodRenderer.
     */
    public void wireIfNeeded() {
        if (this.delegate == null)
            return;

        try {
            IVulkanLodRenderer lodRenderer = (IVulkanLodRenderer) LodRenderer.INSTANCE;
            lodRenderer.dhvulkan$setVulkanDelegate(this.delegate);
            LOGGER.info("[DH-VulkanMod] Dh24RenderDelegate wired into LodRenderer. Ready.");
        } catch (Exception e) {
            LOGGER.error("[DH-VulkanMod] Failed to wire delegate", e);
        }
    }

    /**
     * Called from shared MixinLevelRenderer after MC terrain renders.
     * Delegates to the deferredComposite method on the duck-interfaced LodRenderer.
     */
    public void deferredComposite() {
        try {
            IVulkanLodRenderer lodRenderer = (IVulkanLodRenderer) LodRenderer.INSTANCE;
            lodRenderer.dhvulkan$compositeVulkanFrame();
        } catch (Exception e) {
            LOGGER.error("[DH-VulkanMod] deferredComposite failed", e);
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
