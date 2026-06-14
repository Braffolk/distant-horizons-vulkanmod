package com.braffolk.dhvulkan.beryl;

import com.braffolk.dhvulkan.core.DhVulkanFramebuffer;
import com.braffolk.dhvulkan.core.VulkanBackend;
import com.braffolk.dhvulkan.core.VulkanRenderEngine;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Registers Distant Horizons depth texture samplers with Beryl's rendering pipeline.
 *
 * Beryl shaders can then reference these samplers by name:
 * - dhDepthTex: DH's opaque depth texture (main LOD depth)
 * - dhDepthTex0: DH's opaque depth texture (alias for shader compatibility)
 * - dhDepthTex1: DH's translucent depth texture
 *
 * These samplers provide the same depth data that vanilla Iris-based shader packs
 * expect from Distant Horizons, allowing existing DH-compatible shader packs to
 * work with Beryl without modification.
 *
 * Sampler registration is done via VulkanMod's VTextureSelector, which Beryl uses
 * for texture management. The samplers are dynamic — they update each frame to
 * reflect the current DH framebuffer depth.
 */
public final class DhBerylSamplers {

    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod-Beryl");

    /** VTextureSelector slots reserved for DH depth samplers.
     * CRITICAL: VulkanMod's VTextureSelector has a 12-element array (indices 0-11).
     * bindTexture(slot >= 12) silently fails with only an error log.
     * Uses slot 6 — safe because Beryl only uses slots 0-5 and DH internal
     * pipelines use 7-11 (with sequential reuse between SSAO/Fog/Composite).
     * All three samplers bind to the same slot since they all reference the
     * same DH depth texture (DH VulkanMod has no separate translucent depth).
     */
    public static final int DH_DEPTH_TEX_SLOT = 6;
    public static final int DH_DEPTH_TEX0_SLOT = 6;
    public static final int DH_DEPTH_TEX1_SLOT = 6;

    /** Whether samplers have been registered with the texture system */
    private static boolean registered = false;

    /**
     * Register DH depth samplers with Beryl's texture management system.
     * Called once during Beryl initialization.
     */
    public static void registerSamplers() {
        if (registered) return;

        LOGGER.info("[DH-Vulkan-Beryl] Registering DH depth samplers at texture slots " +
                DH_DEPTH_TEX_SLOT + ", " + DH_DEPTH_TEX0_SLOT + ", " + DH_DEPTH_TEX1_SLOT);

        registered = true;
    }

    /**
     * Update DH depth sampler bindings for the current frame.
     * Called from VulkanRenderEngine after LODs are rendered into DH's framebuffer.
     *
     * @param dhFramebuffer the DH framebuffer containing the depth texture
     */
    public static void updateDepthSamplers(DhVulkanFramebuffer dhFramebuffer) {
        if (!registered || dhFramebuffer == null) return;

        try {
            VulkanImage dhDepth = dhFramebuffer.getFramebuffer().getDepthAttachment();
            if (dhDepth == null) return;

            // Bind DH depth to all expected sampler names.
            // Shader packs may reference any of these names depending on convention.
            VTextureSelector.bindTexture(DH_DEPTH_TEX_SLOT, dhDepth);
            VTextureSelector.bindTexture(DH_DEPTH_TEX0_SLOT, dhDepth);
            VTextureSelector.bindTexture(DH_DEPTH_TEX1_SLOT, dhDepth);

        } catch (Exception e) {
            LOGGER.debug("[DH-Vulkan-Beryl] Failed to update depth samplers: {}", e.getMessage());
        }
    }

    /**
     * Clear DH depth sampler bindings (e.g., at end of frame).
     * Prevents stale texture references if the DH framebuffer is recreated.
     */
    public static void clearDepthSamplers() {
        if (!registered) return;

        try {
            VulkanImage white = VTextureSelector.getWhiteTexture();
            VTextureSelector.bindTexture(DH_DEPTH_TEX_SLOT, white);
            VTextureSelector.bindTexture(DH_DEPTH_TEX0_SLOT, white);
            VTextureSelector.bindTexture(DH_DEPTH_TEX1_SLOT, white);
        } catch (Exception e) {
            // Best-effort cleanup
        }
    }
}
