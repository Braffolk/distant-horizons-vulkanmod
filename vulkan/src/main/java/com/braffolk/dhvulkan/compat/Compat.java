package com.braffolk.dhvulkan.compat;

#if MC_VER>=MC_1_21_1

import net.vulkanmod.vulkan.memory.buffer.VertexBuffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.Buffer;#else
import net.vulkanmod.vulkan.memory.VertexBuffer;
import net.vulkanmod.vulkan.memory.IndexBuffer;
import net.vulkanmod.vulkan.memory.Buffer;#endif

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.texture.VTextureSelector;

import java.nio.ByteBuffer;

/**
 * Single source of truth for ALL version-specific API differences.
 * NO other file should contain #if blocks.
 */
public final class Compat {

    // ========================= //
    // Cached reflection fields  //
    // ========================= //

    private static java.lang.reflect.Field vulkanImageIdField;
    private static java.lang.reflect.Field vulkanImageViewField;
    private static java.lang.reflect.Field rendererCmdBufferField;
    private static java.lang.reflect.Field defaultMainPassAuxField;

    static {
        try {
            vulkanImageIdField = VulkanImage.class.getDeclaredField("id");
            vulkanImageIdField.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            vulkanImageViewField = VulkanImage.class.getDeclaredField("mainImageView");
            vulkanImageViewField.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            rendererCmdBufferField = Renderer.class.getDeclaredField("currentCmdBuffer");
            rendererCmdBufferField.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            defaultMainPassAuxField = net.vulkanmod.vulkan.pass.DefaultMainPass.class.getDeclaredField("auxRenderPass");
            defaultMainPassAuxField.setAccessible(true);
        } catch (Exception ignored) {}
    }

    // ========================= //
    // VulkanMod detection       //
    // ========================= //

    private static final boolean VULKANMOD_ACTIVE = detectVulkanMod();

    private static boolean detectVulkanMod() {
        try {
            Class.forName("net.vulkanmod.vulkan.Renderer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** @return true if VulkanMod is loaded (no GL context available) */
    public static boolean isVulkanModActive() {
        return VULKANMOD_ACTIVE;
    }

    // ========================= //
    // Buffer factories          //
    // ========================= //

    public static Object createVertexBuffer(int sizeBytes) {
        return new VertexBuffer(sizeBytes, MemoryTypes.HOST_MEM);
    }

    public static Object createGpuVertexBuffer(int sizeBytes) {
        return new VertexBuffer(sizeBytes, MemoryTypes.GPU_MEM);
    }

    public static Object createIndexBuffer(int sizeBytes) {
        #if MC_VER >= MC_1_21_1
        return new IndexBuffer(sizeBytes, MemoryTypes.HOST_MEM, IndexBuffer.IndexType.UINT32);
        #else
        return new IndexBuffer(sizeBytes, MemoryTypes.HOST_MEM);
        #endif
    }

    // ========================= //
    // Buffer operations //
    // ========================= //

    /**
     * Wait for the GPU to finish all in-flight work.
     * Must be called before freeing resources that may still be in use by
     * a previous frame. This is expensive — only use during cleanup/reinit,
     * never per-frame.
     */
    public static void waitDeviceIdle() {
        org.lwjgl.vulkan.VK10.vkDeviceWaitIdle(net.vulkanmod.vulkan.Vulkan.getVkDevice());
    }

    public static long getBufferId(Object buffer) {
        return ((Buffer) buffer).getId();
    }

    public static void scheduleFree(Object buffer) {
        #if MC_VER >= MC_1_21_1
        ((Buffer) buffer).scheduleFree();
        #else
        ((Buffer) buffer).freeBuffer();
        #endif
    }

    public static void copyBuffer(Object buffer, ByteBuffer data, int size) {
        #if MC_VER >= MC_1_21_1
        ((Buffer) buffer).copyBuffer(data, size);
        #else
        if (buffer instanceof VertexBuffer) {
            ((VertexBuffer) buffer).copyToVertexBuffer(size, 1, data);
        } else if (buffer instanceof IndexBuffer) {
            ((IndexBuffer) buffer).copyBuffer(data);
        }
        #endif
    }

    // ========================= //
    // Draw calls //
    // ========================= //

    public static void draw(Object vertexBuffer, int vertexCount) {
        Renderer.getDrawer().draw((VertexBuffer) vertexBuffer, vertexCount);
    }

    public static void drawIndexed(Object vertexBuffer, Object indexBuffer, int indexCount) {
        #if MC_VER >= MC_1_21_1
        Renderer.getInstance().getDrawer().drawIndexed(
                (Buffer) vertexBuffer, (IndexBuffer) indexBuffer, indexCount);
        #else
        // VM 0.4.2 Drawer.drawIndexed() hardcodes VK_INDEX_TYPE_UINT16 (= 0),
        // but DH uses UINT32 indices. Issue raw Vulkan commands with UINT32.
        org.lwjgl.vulkan.VkCommandBuffer cmd = Renderer.getCommandBuffer();
        VertexBuffer vb = (VertexBuffer) vertexBuffer;
        IndexBuffer ib = (IndexBuffer) indexBuffer;

        // Bind vertex buffer
        long pBuf = org.lwjgl.system.MemoryUtil.nmemAllocChecked(8);
        long pOff = org.lwjgl.system.MemoryUtil.nmemAllocChecked(8);
        org.lwjgl.system.MemoryUtil.memPutLong(pBuf, vb.getId());
        org.lwjgl.system.MemoryUtil.memPutLong(pOff, vb.getOffset());
        org.lwjgl.vulkan.VK10.nvkCmdBindVertexBuffers(cmd, 0, 1, pBuf, pOff);
        org.lwjgl.system.MemoryUtil.nmemFree(pBuf);
        org.lwjgl.system.MemoryUtil.nmemFree(pOff);

        // Bind index buffer with VK_INDEX_TYPE_UINT32 = 1
        org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer(cmd, ib.getId(), ib.getOffset(), 1);

        // Draw
        org.lwjgl.vulkan.VK10.vkCmdDrawIndexed(cmd, indexCount, 1, 0, 0, 0);
        #endif
    }

    // ========================= //
    // VertexFormatElement //
    // ========================= //

    public static VertexFormatElement vertexFormatElement(
            int id, int index,
            VertexFormatElement.Type type, VertexFormatElement.Usage usage,
            int count) {
        #if MC_VER >= MC_1_21_1
        return new VertexFormatElement(id, index, type, usage, count);
        #else
        // MC 1.20: VertexFormatElement(id, Type, Usage, count)
        // Non-UV usages MUST have id=0 (MC validates this at construction).
        // CRITICAL: Do NOT remap GENERIC→UV! VulkanMod's UV handler doesn't
        // support INT type, resulting in VK_FORMAT_UNDEFINED → DEVICE_LOST.
        // GENERIC with id=0 is valid and VulkanMod maps INT→VK_FORMAT_R32_SINT.
        if (usage == VertexFormatElement.Usage.UV) {
            return new VertexFormatElement(id, type, usage, count);
        }
        return new VertexFormatElement(0, type, usage, count);
        #endif
    }

    // ========================= //
    // VertexFormat builder //
    // ========================= //

    public static VertexFormat buildVertexFormat(String[] names, VertexFormatElement[] elements) {
        #if MC_VER >= MC_1_21_1
        VertexFormat.Builder builder = VertexFormat.builder();
        for (int i = 0; i < names.length; i++) {
            builder.add(names[i], elements[i]);
        }
        return builder.build();
        #else
        com.google.common.collect.ImmutableMap.Builder<String, VertexFormatElement> map =
                com.google.common.collect.ImmutableMap.builder();
        for (int i = 0; i < names.length; i++) {
            map.put(names[i], elements[i]);
        }
        return new VertexFormat(map.build());
        #endif
    }

    // ========================= //
    // Renderer / SwapChain //
    // ========================= //

    public static int getSwapChainWidth() {
        #if MC_VER >= MC_1_21_1
        return Renderer.getInstance().getSwapChain().getWidth();
        #else
        return net.vulkanmod.vulkan.Vulkan.getSwapChain().getWidth();
        #endif
    }

    public static int getSwapChainHeight() {
        #if MC_VER >= MC_1_21_1
        return Renderer.getInstance().getSwapChain().getHeight();
        #else
        return net.vulkanmod.vulkan.Vulkan.getSwapChain().getHeight();
        #endif
    }

    public static void beginRenderPass(
            net.vulkanmod.vulkan.framebuffer.RenderPass renderPass,
            net.vulkanmod.vulkan.framebuffer.Framebuffer framebuffer) {
        #if MC_VER >= MC_1_21_1
        Renderer.getInstance().beginRenderPass(renderPass, framebuffer);
        #else
        try {
            // End any currently active render pass first
            Renderer.getInstance().endRenderPass();

            org.lwjgl.vulkan.VkCommandBuffer cmdBuffer =
                    (org.lwjgl.vulkan.VkCommandBuffer) rendererCmdBufferField.get(Renderer.getInstance());
            org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush();
            framebuffer.beginRenderPass(cmdBuffer, renderPass, stack);
            stack.close();

            // CRITICAL: update Renderer's state so endRenderPass() works.
            // Without this, endRenderPass() sees boundFramebuffer==null and no-ops,
            // leaving nested render passes open → DEVICE_LOST on NVIDIA.
            Renderer.getInstance().setBoundFramebuffer(framebuffer);
            Renderer.getInstance().setBoundRenderPass(renderPass);
        } catch (Exception e) {
            throw new RuntimeException("[DH-Vulkan] Failed to begin render pass on VM 0.4.2", e);
        }
        #endif
    }

    /**
     * Rebind MC's main render target (swapchain) so the composite can draw into it.
     * On VM 0.6.1, DefaultMainPass.rebindMainTarget() handles state internally.
     * On VM 0.4.2, it calls swapChain.beginRenderPass() directly without updating
     * Renderer state — we must fix up boundFramebuffer/boundRenderPass afterward.
     */
    public static void rebindMainTarget() {
        net.vulkanmod.vulkan.pass.DefaultMainPass mainPass =
                (net.vulkanmod.vulkan.pass.DefaultMainPass) Renderer.getInstance().getMainPass();
        mainPass.rebindMainTarget();

        #if MC_VER < MC_1_21_1
        // VM 0.4.2: rebindMainTarget starts auxRenderPass on swapChain but doesn't
        // update Renderer state. Fix up so bindGraphicsPipeline/endRenderPass work.
        try {
            net.vulkanmod.vulkan.framebuffer.RenderPass auxPass =
                    (net.vulkanmod.vulkan.framebuffer.RenderPass) defaultMainPassAuxField.get(mainPass);
            Renderer.getInstance().setBoundFramebuffer(net.vulkanmod.vulkan.Vulkan.getSwapChain());
            Renderer.getInstance().setBoundRenderPass(auxPass);
        } catch (Exception e) {
            throw new RuntimeException("[DH-Vulkan] Failed to rebind main target on VM 0.4.2", e);
        }
        #endif
    }

    // ========================= //
    // Uniform setup //
    // ========================= //

    public static void addUniformWithBuffer(
            net.vulkanmod.vulkan.shader.layout.AlignedStruct.Builder builder,
            String type, String name, int count,
            java.util.function.Supplier<net.vulkanmod.vulkan.util.MappedBuffer> bufferSupplier) {
        #if MC_VER >= MC_1_21_1
        net.vulkanmod.vulkan.shader.layout.Uniform.Info info =
                net.vulkanmod.vulkan.shader.layout.Uniform.createUniformInfo(type, name, count);
        info.setBufferSupplier(bufferSupplier);
        builder.addUniformInfo(info);
        #else
        builder.addUniformInfo(type, name, count);
        #endif
    }

    /**
     * On VM 0.4.2, Uniform.setSupplier() in the constructor only resolves MC's
     * built-in uniforms (ModelViewMat etc). Custom DH uniforms have values=null.
     * Call this AFTER buildUBO() to set suppliers on each Uniform.
     * On VM 0.6.1, this is a no-op (Info.setBufferSupplier handles it).
     */
    public static void setUniformSuppliers(
            net.vulkanmod.vulkan.shader.descriptor.UBO ubo,
            java.util.Map<String, net.vulkanmod.vulkan.util.MappedBuffer> bufferMap) {
        #if MC_VER < MC_1_21_1
        for (net.vulkanmod.vulkan.shader.layout.Uniform uniform : ubo.getUniforms()) {
            net.vulkanmod.vulkan.util.MappedBuffer mb = bufferMap.get(uniform.getName());
            if (mb != null) {
                uniform.setSupplier(() -> mb);
            }
        }
        #endif
    }

    // ========================= //
    // GlTexture / Lightmap //
    // ========================= //

    public static VulkanImage getLightmapVulkanImage() {
        try {
            #if MC_VER >= MC_1_21_1
            var lightmapView = net.minecraft.client.Minecraft.getInstance()
                    .gameRenderer.lightTexture().getTextureView();
            if (lightmapView == null) return null;
            com.mojang.blaze3d.opengl.GlTexture glTex =
                    (com.mojang.blaze3d.opengl.GlTexture) lightmapView.texture();
            net.vulkanmod.gl.VkGlTexture vkGlTex =
                    net.vulkanmod.gl.VkGlTexture.getTexture(glTex.glId());
            return vkGlTex != null ? vkGlTex.getVulkanImage() : null;
            #else
            // VM 0.4.2: MC already binds lightmap to slot 2 before our hook fires.
            // Just read it directly from VTextureSelector.
            return VTextureSelector.getBoundTexture(2);
            #endif
        } catch (Exception e) {
            return null;
        }
    }

    // ========================= //
    // Config value scaling //
    // ========================= //

    /**
     * DH 1.20.6 config returns noise intensity as an unscaled integer (e.g. 40 = 40%).
     * DH 1.21.1+ returns it as a pre-scaled 0-1 float. Normalize here.
     */
    public static float scaleNoiseIntensity(float raw) {
        #if MC_VER < MC_1_21_1
        return raw * 0.01f;
        #else
        return raw;
        #endif
    }

    // ========================= //
    // Swapchain access //
    // ========================= //

    /**
     * Get MC's swapchain depth attachment for depth-compared compositing.
     * Returns the VulkanImage backing MC's depth buffer.
     */
    public static VulkanImage getSwapChainDepthAttachment() {
        #if MC_VER >= MC_1_21_1
        return Renderer.getInstance().getSwapChain().getDepthAttachment();
        #else
        return net.vulkanmod.vulkan.Vulkan.getSwapChain().getDepthAttachment();
        #endif
    }

    // ========================= //
    // Depth-only sampling //
    // ========================= //

    /** Cached depth-only image view for D24S8 / D32S8 formats */
    private static long depthOnlyView = 0;
    private static long lastDepthImageId = 0;
    /** Saved original view for restore after draw */
    private static long savedOriginalView = 0;
    private static VulkanImage savedDepthImage = null;

    /**
     * Prepare MC's depth texture for sampling by setting the depth-only
     * image view on combined D+S formats and binding to the given slot.
     * <p>
     * IMPORTANT: Does NOT restore the original view — you MUST call
     * {@link #restoreMcDepthView()} after the draw completes.
     * This is necessary because VTextureSelector.bindTexture only records
     * the image reference; the actual descriptor is written later during
     * uploadAndBindUBOs, which reads mainImageView at that point.
     */
    public static void prepareMcDepthForSampling(int slot, VulkanImage depthImage) {
        int VK_IMAGE_ASPECT_STENCIL_BIT = 4;
        int VK_IMAGE_ASPECT_DEPTH_BIT = 2;

        if ((depthImage.aspect & VK_IMAGE_ASPECT_STENCIL_BIT) != 0) {
            try {
                long imageId = (long) vulkanImageIdField.get(depthImage);

                if (imageId != lastDepthImageId || depthOnlyView == 0) {
                    if (depthOnlyView != 0) {
                        org.lwjgl.vulkan.VK10.vkDestroyImageView(
                                net.vulkanmod.vulkan.Vulkan.getVkDevice(), depthOnlyView, null);
                    }
                    depthOnlyView = VulkanImage.createImageView(
                            imageId, depthImage.format,
                            VK_IMAGE_ASPECT_DEPTH_BIT,
                            0 /* VK_IMAGE_VIEW_TYPE_2D */, depthImage.mipLevels);
                    lastDepthImageId = imageId;
                    LOGGER.info("[DH-Vulkan] Created depth-only view for D+S format={} (view={})",
                            depthImage.format, depthOnlyView);
                }

                savedOriginalView = (long) vulkanImageViewField.get(depthImage);
                savedDepthImage = depthImage;
                vulkanImageViewField.setLong(depthImage, depthOnlyView);

                VTextureSelector.bindTexture(slot, depthImage);
                // DO NOT restore here — descriptor set write happens later
            } catch (Exception e) {
                LOGGER.error("[DH-Vulkan] Failed to create depth-only view, binding as-is", e);
                savedDepthImage = null;
                VTextureSelector.bindTexture(slot, depthImage);
            }
        } else {
            savedDepthImage = null;
            VTextureSelector.bindTexture(slot, depthImage);
        }
    }

    /**
     * Restore the original mainImageView on the depth image after draw.
     * Must be called after the draw that samples MC depth completes.
     */
    public static void restoreMcDepthView() {
        if (savedDepthImage != null && savedOriginalView != 0) {
            try {
                vulkanImageViewField.setLong(savedDepthImage, savedOriginalView);
            } catch (Exception e) {
                LOGGER.error("[DH-Vulkan] Failed to restore MC depth view", e);
            }
            savedDepthImage = null;
            savedOriginalView = 0;
        }
    }

    /**
     * Convenience: prepare, bind, and restore in one call.
     * Use when the full bind→upload→draw cycle happens within the caller.
     * 
     * @deprecated Use {@link #prepareMcDepthForSampling} +
     *             {@link #restoreMcDepthView} instead
     */
    public static void bindMcDepthForSampling(int slot, VulkanImage depthImage) {
        prepareMcDepthForSampling(slot, depthImage);
        // Note: caller must call restoreMcDepthView() after draw
    }

    // ========================= //
    // MC depth copy for deferred //
    // ========================= //

    /** Persistent depth copy image — recreated on resize */
    private static VulkanImage mcDepthCopyImage = null;
    private static int mcDepthCopyWidth = 0;
    private static int mcDepthCopyHeight = 0;

    /**
     * Copy MC's swapchain depth to a separate image for sampling.
     * <p>
     * Must be called AFTER endRenderPass() (so the depth is not an active
     * attachment) and BEFORE rebindMainTarget() (which re-attaches it).
     * The returned image is safe to sample during the composite pass because
     * it is NOT bound as an attachment.
     * <p>
     * The copy image is persistent and only recreated on resize.
     */
    public static VulkanImage copyMcDepthForSampling(VulkanImage srcDepth) {
        try {
            // Recreate copy image if dimensions changed
            if (mcDepthCopyImage == null
                    || mcDepthCopyWidth != srcDepth.width
                    || mcDepthCopyHeight != srcDepth.height) {
                if (mcDepthCopyImage != null) {
                    mcDepthCopyImage.free();
                }
                // Create with TRANSFER_DST (receive copy) + SAMPLED (sample in shader)
                int copyUsage = 0x04 /* VK_IMAGE_USAGE_SAMPLED_BIT */
                        | 0x08 /* VK_IMAGE_USAGE_TRANSFER_DST_BIT */;
                mcDepthCopyImage = VulkanImage.createDepthImage(
                        srcDepth.format, srcDepth.width, srcDepth.height,
                        copyUsage, false, true);
                mcDepthCopyWidth = srcDepth.width;
                mcDepthCopyHeight = srcDepth.height;
                LOGGER.info("[DH-Vulkan] Created MC depth copy image: {}x{} format={}",
                        srcDepth.width, srcDepth.height, srcDepth.format);
            }

            // Get the current command buffer from Renderer
            org.lwjgl.vulkan.VkCommandBuffer cmdBuffer = (org.lwjgl.vulkan.VkCommandBuffer) rendererCmdBufferField
                    .get(Renderer.getInstance());

            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                // Transition src depth to TRANSFER_SRC
                srcDepth.transitionImageLayout(stack, cmdBuffer,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

                // Transition copy to TRANSFER_DST
                mcDepthCopyImage.transitionImageLayout(stack, cmdBuffer,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

                // Copy depth
                long srcId = (long) vulkanImageIdField.get(srcDepth);
                long dstId = (long) vulkanImageIdField.get(mcDepthCopyImage);

                org.lwjgl.vulkan.VkImageCopy.Buffer copyRegion = org.lwjgl.vulkan.VkImageCopy.calloc(1, stack);
                copyRegion.get(0)
                        .srcSubresource(s -> s
                                .aspectMask(srcDepth.aspect)
                                .mipLevel(0).baseArrayLayer(0).layerCount(1))
                        .srcOffset(o -> o.set(0, 0, 0))
                        .dstSubresource(s -> s
                                .aspectMask(srcDepth.aspect)
                                .mipLevel(0).baseArrayLayer(0).layerCount(1))
                        .dstOffset(o -> o.set(0, 0, 0))
                        .extent(e -> e.set(srcDepth.width, srcDepth.height, 1));

                org.lwjgl.vulkan.VK10.vkCmdCopyImage(cmdBuffer,
                        srcId, org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        dstId, org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        copyRegion);

                // Transition copy to SHADER_READ_ONLY for sampling
                mcDepthCopyImage.transitionImageLayout(stack, cmdBuffer,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }

            return mcDepthCopyImage;
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Failed to copy MC depth", e);
            return srcDepth; // fallback to original (may not work on NVIDIA)
        }
    }

    /** Clean up the persistent depth copy image */
    public static void cleanupDepthCopy() {
        if (mcDepthCopyImage != null) {
            mcDepthCopyImage.free();
            mcDepthCopyImage = null;
        }
        mcDepthCopyWidth = 0;
        mcDepthCopyHeight = 0;
    }

    /**
     * Free all static Vulkan resources held by Compat.
     * Must be called during VulkanRenderDelegate.cleanup() to prevent leaks
     * across server transitions (leave/join world cycles).
     */
    public static void cleanupStaticResources() {
        // Free the depth-only image view used for combined D+S formats
        if (depthOnlyView != 0) {
            org.lwjgl.vulkan.VK10.vkDestroyImageView(
                    net.vulkanmod.vulkan.Vulkan.getVkDevice(), depthOnlyView, null);
            depthOnlyView = 0;
        }
        lastDepthImageId = 0;
        savedOriginalView = 0;
        savedDepthImage = null;

        // Free the persistent MC depth copy image
        cleanupDepthCopy();
    }

    private static final com.seibel.distanthorizons.core.logging.DhLogger LOGGER = new com.seibel.distanthorizons.core.logging.DhLoggerBuilder()
            .build();

    private Compat() {
    }
}
