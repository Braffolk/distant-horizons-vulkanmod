package com.braffolk.dhvulkan.mixin.beryl;

import com.braffolk.dhvulkan.beryl.BerylCompat;
import com.braffolk.dhvulkan.beryl.DhBerylSamplers;
import com.braffolk.dhvulkan.core.VulkanBackend;
import com.braffolk.dhvulkan.core.VulkanRenderEngine;
import com.braffolk.dhvulkan.core.DhVulkanFramebuffer;
import com.braffolk.dhvulkan.bridge.DhIntegration;
import com.braffolk.dhvulkan.DhVulkanModEntrypoint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Mixin into Beryl's ShaderMainPass to integrate DH LOD rendering.
 *
 * Beryl 0.2.0-alpha uses begin(VkCommandBuffer, MemoryStack) and
 * end(VkCommandBuffer) as the main pass lifecycle methods (not beginPass/endPass).
 * We also target renderOpaqueTerrain and renderTranslucentTerrain for depth
 * sampler updates.
 *
 * All injections use require=0, expect=0 for version compatibility.
 */
@Mixin(value = net.beryl.render.ShaderMainPass.class, remap = false)
public class MixinShaderMainPass {

    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod-Beryl");

    /**
     * After Beryl's opaque terrain pass completes, bind DH depth samplers
     * so Beryl's post-processing shaders can read DH's LOD depth.
     */
    @Inject(
            method = "renderOpaqueTerrain",
            at = @At("RETURN"),
            require = 0,
            expect = 0
    )
    private void dhvulkan$afterBerylOpaqueTerrain(CallbackInfo ci) {
        if (!BerylCompat.shouldUseVulkanWithBeryl()) return;
        updateDhResources();
    }

    /**
     * After Beryl's translucent terrain pass, ensure DH translucent depth
     * is bound for correct compositing.
     */
    @Inject(
            method = "renderTranslucentTerrain",
            at = @At("RETURN"),
            require = 0,
            expect = 0
    )
    private void dhvulkan$afterBerylTranslucentTerrain(CallbackInfo ci) {
        if (!BerylCompat.shouldUseVulkanWithBeryl()) return;
        updateDhResources();
    }

    /**
     * After Beryl's main pass begins (begin(VkCommandBuffer, MemoryStack)),
     * ensure DH's Vulkan resources are initialized.
     */
    @Inject(
            method = "begin",
            at = @At("RETURN"),
            require = 0,
            expect = 0
    )
    private void dhvulkan$afterBerylBegin(CallbackInfo ci) {
        if (!BerylCompat.shouldUseVulkanWithBeryl()) return;

        DhIntegration integration = DhVulkanModEntrypoint.getActiveIntegration();
        if (integration == null) return;

        VulkanBackend backend = integration.getBackend();
        if (backend instanceof VulkanRenderEngine engine && !engine.isInitialized()) {
            backend.init();
        }
    }

    /**
     * After Beryl's main pass ends (end(VkCommandBuffer)),
     * clean up and signal DH rendering complete.
     */
    @Inject(
            method = "end",
            at = @At("RETURN"),
            require = 0,
            expect = 0
    )
    private void dhvulkan$afterBerylEnd(CallbackInfo ci) {
        if (!BerylCompat.shouldUseVulkanWithBeryl()) return;
        BerylCompat.onDhRenderingComplete();
        DhBerylSamplers.clearDepthSamplers();
    }

    /**
     * Also hook beginPass as fallback for older Beryl versions that
     * may use different method names. With require=0, silently skips
     * if the method doesn't exist.
     */
    @Inject(
            method = "beginPass",
            at = @At("RETURN"),
            require = 0,
            expect = 0
    )
    private void dhvulkan$afterBerylBeginPassFallback(CallbackInfo ci) {
        if (!BerylCompat.shouldUseVulkanWithBeryl()) return;

        DhIntegration integration = DhVulkanModEntrypoint.getActiveIntegration();
        if (integration == null) return;

        VulkanBackend backend = integration.getBackend();
        if (backend instanceof VulkanRenderEngine engine && !engine.isInitialized()) {
            backend.init();
        }
    }

    @Inject(
            method = "endPass",
            at = @At("RETURN"),
            require = 0,
            expect = 0
    )
    private void dhvulkan$afterBerylEndPassFallback(CallbackInfo ci) {
        if (!BerylCompat.shouldUseVulkanWithBeryl()) return;
        BerylCompat.onDhRenderingComplete();
        DhBerylSamplers.clearDepthSamplers();
    }

    private static void updateDhResources() {
        try {
            DhIntegration integration = DhVulkanModEntrypoint.getActiveIntegration();
            if (integration == null) return;

            VulkanBackend backend = integration.getBackend();
            if (!(backend instanceof VulkanRenderEngine engine)) return;

            DhVulkanFramebuffer dhFb = engine.getDhFramebuffer();
            if (dhFb != null) {
                DhBerylSamplers.updateDepthSamplers(dhFb);
            }
        } catch (Exception e) {
            LOGGER.debug("[DH-Vulkan-Beryl] Failed to update DH resources: {}", e.getMessage());
        }
    }
}
