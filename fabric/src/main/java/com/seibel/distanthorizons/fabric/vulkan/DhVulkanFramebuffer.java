/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    DH-owned Vulkan framebuffer for Phase 6 render pass integration.
 */

package com.seibel.distanthorizons.fabric.vulkan;

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;

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
        // 1 color attachment (RGBA8) + depth attachment (matching MC's depth format)
        // Framebuffer.Builder auto-sets SAMPLED_BIT on both attachments
        this.framebuffer = new Framebuffer.Builder(this.width, this.height, 1, true)
                .build();

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
        int newWidth = Renderer.getInstance().getSwapChain().getWidth();
        int newHeight = Renderer.getInstance().getSwapChain().getHeight();

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
        Renderer.getInstance().beginRenderPass(this.renderPass, this.framebuffer);
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
