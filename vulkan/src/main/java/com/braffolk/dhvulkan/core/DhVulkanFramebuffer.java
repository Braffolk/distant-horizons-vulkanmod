/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    DH-owned Vulkan framebuffer for Phase 6 render pass integration.
 */

package com.braffolk.dhvulkan.core;

import com.braffolk.dhvulkan.compat.Compat;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;

import net.vulkanmod.vulkan.texture.VulkanImage;

/**
 * Manages DH's private Vulkan framebuffer with color (RGBA8) and depth
 * attachments. LODs are rendered into this framebuffer, then composited
 * onto MC's main framebuffer.
 * <p>
 * Images are created with both {@code SAMPLED_BIT} and {@code ATTACHMENT_BIT}
 * so they can be used as both render targets and shader inputs (VulkanMod's
 * {@link Framebuffer#createImages()} provides this by default).
 */
public class DhVulkanFramebuffer {
    private static final DhLogger LOGGER = new DhLoggerBuilder().build();

    // Vulkan constants (from VK10) — inlined to avoid compile-time LWJGL dependency
    private static final int VK_FORMAT_R8G8B8A8_UNORM = 37;
    private static final int VK_FORMAT_D32_SFLOAT = 126;
    private static final int VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT = 16; // 0x10
    private static final int VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT = 32; // 0x20
    private static final int VK_IMAGE_USAGE_SAMPLED_BIT = 4; // 0x04
    private static final int VK_ATTACHMENT_LOAD_OP_CLEAR = 1;
    private static final int VK_ATTACHMENT_STORE_OP_STORE = 0;
    private static final int VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL = 5;

    private Framebuffer framebuffer;
    private RenderPass renderPass;

    private int width;
    private int height;

    /**
     * Creates the DH framebuffer matching the given dimensions and registers
     * a resize callback with VulkanMod so the framebuffer is recreated when
     * the swap chain changes.
     */
    public void init(int width, int height) {
        this.width = width;
        this.height = height;

        createFramebufferAndPass();

        // Recreate when VulkanMod resizes the swap chain
        Renderer.getInstance().addOnResizeCallback(this::onResize);

        LOGGER.info("[DH-Vulkan] DhVulkanFramebuffer created ({}x{})", width, height);
    }

    private void createFramebufferAndPass() {
        // Create images manually so we can force D32_SFLOAT for the depth attachment.
        // VulkanMod's default depth format on NVIDIA is D24_S8 (depth-stencil), whose
        // image view includes stencil aspect bits and cannot be sampled as sampler2D
        // by the composite/SSAO/fog shaders.
        VulkanImage colorImage = VulkanImage.builder(this.width, this.height)
                .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                .setLinearFiltering(true)
                .setClamp(true)
                .createVulkanImage();

        VulkanImage depthImage = VulkanImage.createDepthImage(
                VK_FORMAT_D32_SFLOAT,
                this.width, this.height,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                false, true);

        this.framebuffer = Framebuffer.builder(colorImage, depthImage).build();

        // Render pass: clear both attachments, store both, final layout =
        // SHADER_READ_ONLY
        // so the textures can be sampled by the composite shader after the pass ends.
        RenderPass.Builder rpBuilder = RenderPass.builder(this.framebuffer);
        // Color: already CLEAR+STORE by default, just set final layout
        rpBuilder.getColorAttachmentInfo()
                .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        // Depth: default is CLEAR+DONT_CARE, override to STORE for composite sampling
        rpBuilder.getDepthAttachmentInfo()
                .setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE)
                .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        this.renderPass = rpBuilder.build();
    }

    /**
     * Called when VulkanMod recreates the swap chain (window resize, frame queue
     * size change, etc.). Recreates the framebuffer at the new swap chain
     * dimensions.
     */
    private void onResize() {
        // Guard against stale callbacks after cleanup
        if (this.framebuffer == null || this.renderPass == null)
            return;

        int newWidth = Compat.getSwapChainWidth();
        int newHeight = Compat.getSwapChainHeight();

        if (newWidth == 0 || newHeight == 0) {
            return; // Minimized window
        }

        if (newWidth == this.width && newHeight == this.height) {
            return; // No change
        }

        LOGGER.info("[DH-Vulkan] Resizing DhVulkanFramebuffer: {}x{} -> {}x{}",
                this.width, this.height, newWidth, newHeight);

        // Clean up old render pass and framebuffer
        this.renderPass.cleanUp();
        this.framebuffer.cleanUp();

        this.width = newWidth;
        this.height = newHeight;

        createFramebufferAndPass();
    }

    /**
     * Begin DH's render pass. This will clear color to (0,0,0,0) and depth to 1.0.
     * Must be called after ending/leaving MC's render pass.
     */
    public void beginRenderPass() {
        Compat.beginRenderPass(this.renderPass, this.framebuffer);
    }

    public Framebuffer getFramebuffer() {
        return this.framebuffer;
    }

    public RenderPass getRenderPass() {
        return this.renderPass;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public void cleanup() {
        if (this.renderPass != null) {
            this.renderPass.cleanUp();
            this.renderPass = null;
        }
        if (this.framebuffer != null) {
            this.framebuffer.cleanUp();
            this.framebuffer = null;
        }
        LOGGER.info("[DH-Vulkan] DhVulkanFramebuffer cleaned up.");
    }
}
