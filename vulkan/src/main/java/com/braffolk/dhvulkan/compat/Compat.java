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
    private static java.lang.reflect.Field vulkanImageLayoutField;
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
            vulkanImageLayoutField = VulkanImage.class.getDeclaredField("currentLayout");
            vulkanImageLayoutField.setAccessible(true);
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
    // Deferred composite hook   //
    // ========================= //

    /**
     * Static hook for Phase 2 deferred composite.
     * Set by MixinLodRenderer (dh24 module), called by MixinLevelRenderer (shared module).
     * This bridges the module boundary without requiring shared code to import dh24 types.
     */
    private static Runnable deferredCompositeHook;
    /** Per-frame flag: true if Phase 2a (addCloudsPass) already fired this frame. */
    private static boolean deferredCompositeRanThisFrame = false;

    public static void setDeferredCompositeHook(Runnable hook) {
        deferredCompositeHook = hook;
    }

    public static void runDeferredCompositeHook() {
        deferredCompositeRanThisFrame = true;
        Runnable hook = deferredCompositeHook;
        if (hook != null) hook.run();
    }

    /**
     * Static hook for Phase 2b late re-composite.
     * Set by MixinLodRenderer (dh24 module), called by MixinLevelRenderer (shared module)
     * at renderLevel @RETURN.
     *
     * On MC 1.20.6 where addCloudsPass doesn't exist, the deferred composite
     * hook is also called here as fallback.
     */
    private static Runnable lateCompositeHook;

    public static void setLateCompositeHook(Runnable hook) {
        lateCompositeHook = hook;
    }

    public static void runLateCompositeHook() {
        // If Phase 2a didn't fire (MC 1.20.6 — no addCloudsPass), run it now
        if (!deferredCompositeRanThisFrame) {
            Runnable deferred = deferredCompositeHook;
            if (deferred != null) deferred.run();
        }
        deferredCompositeRanThisFrame = false; // reset for next frame

        Runnable hook = lateCompositeHook;
        if (hook != null) hook.run();
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
     * vm.5 logic: only create a depth-only view for combined depth+stencil
     * formats (D24S8, D32S8). For depth-only formats like D32_SFLOAT (Mac),
     * the default mainImageView already has DEPTH_BIT aspect — just bind directly.
     * <p>
     * IMPORTANT: Does NOT restore the original view — you MUST call
     * {@link #restoreMcDepthView()} after the draw completes.
     */
    public static void prepareMcDepthForSampling(int slot, VulkanImage depthImage) {
        int VK_IMAGE_ASPECT_STENCIL_BIT = 4;
        int VK_IMAGE_ASPECT_DEPTH_BIT = 2;

        // vm.5 logic: only create depth-only view for combined D+S formats
        // (aspect has stencil bit set). D32_SFLOAT (aspect=2, no stencil)
        // works fine with the default mainImageView.
        if ((depthImage.aspect & VK_IMAGE_ASPECT_STENCIL_BIT) != 0) {
            try {
                long imageId = (long) vulkanImageIdField.get(depthImage);

                if (imageId != lastDepthImageId || depthOnlyView == 0) {
                    if (depthOnlyView != 0) {
                        org.lwjgl.vulkan.VK10.vkDestroyImageView(
                                net.vulkanmod.vulkan.Vulkan.getVkDevice(), depthOnlyView, null);
                    }
                    // vm.5 used the 5-param overload which maps to createImageView(id, viewType=1, format, aspect, arrayLayers=0→1, baseMip=0, mips)
                    depthOnlyView = VulkanImage.createImageView(
                            imageId, depthImage.format,
                            VK_IMAGE_ASPECT_DEPTH_BIT,
                            1, depthImage.mipLevels);
                    lastDepthImageId = imageId;
                    LOGGER.info("[DH-Vulkan] Created depth-only view for D+S format={} aspect={} (view={})",
                            depthImage.format, depthImage.aspect, depthOnlyView);
                }

                savedOriginalView = (long) vulkanImageViewField.get(depthImage);
                savedDepthImage = depthImage;
                vulkanImageViewField.setLong(depthImage, depthOnlyView);

                VTextureSelector.bindTexture(slot, depthImage);
            } catch (Exception e) {
                LOGGER.error("[DH-Vulkan] Failed to create depth-only view, binding as-is", e);
                savedDepthImage = null;
                VTextureSelector.bindTexture(slot, depthImage);
            }
        } else {
            // D32_SFLOAT or other depth-only formats — just bind directly
            savedDepthImage = null;
            VTextureSelector.bindTexture(slot, depthImage);
        }
    }

    /** Check if a VkFormat is a depth or depth-stencil format. */
    private static boolean isDepthFormat(int format) {
        // VK_FORMAT_D16_UNORM = 124
        // VK_FORMAT_X8_D24_UNORM_PACK32 = 125
        // VK_FORMAT_D32_SFLOAT = 126
        // VK_FORMAT_S8_UINT = 127 (stencil only, not depth)
        // VK_FORMAT_D16_UNORM_S8_UINT = 128
        // VK_FORMAT_D24_UNORM_S8_UINT = 129
        // VK_FORMAT_D32_SFLOAT_S8_UINT = 130
        return format >= 124 && format <= 130 && format != 127;
    }

    /**
     * Force-set the currentLayout field on a VulkanImage via reflection.
     * <p>
     * VulkanMod's render pass finalLayout transitions update the GPU layout
     * but do NOT update the software-tracked currentLayout field. This causes
     * transitionImageLayout() to skip barriers on subsequent frames
     * (it returns early when currentLayout == newLayout).
     * <p>
     * After endRenderPass, the depth attachment's GPU layout is
     * DEPTH_STENCIL_ATTACHMENT_OPTIMAL (render pass finalLayout), but
     * currentLayout may still say SHADER_READ_ONLY_OPTIMAL from our
     * previous frame's transition. Calling this forces currentLayout to
     * match the actual GPU state so the next transition works correctly.
     */
    public static void forceDepthLayout(VulkanImage depthImage) {
        if (vulkanImageLayoutField == null) return;
        try {
            vulkanImageLayoutField.setInt(depthImage,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Failed to force depth layout", e);
        }
    }

    /**
     * Transition MC's depth image to SHADER_READ_ONLY_OPTIMAL for sampling.
     * <p>
     * Must be called AFTER endRenderPass() (depth is no longer an active
     * attachment) and BEFORE any render pass that samples the depth image.
     * <p>
     * Since VulkanMod's render pass endRenderPass() transitions depth to
     * DEPTH_STENCIL_ATTACHMENT_OPTIMAL (layout 3) but may not update the
     * software-tracked currentLayout field consistently, we first force
     * currentLayout to match the actual GPU state, then transition.
     */
    public static void transitionDepthForSampling(VulkanImage depthImage) {
        try {
            // Force currentLayout to match actual GPU state after endRenderPass.
            // The render pass finalLayout puts depth in DEPTH_STENCIL_ATTACHMENT_OPTIMAL (3).
            forceDepthLayout(depthImage);

            // Now transition from DEPTH_STENCIL_ATTACHMENT (3) → SHADER_READ_ONLY (5)
            org.lwjgl.vulkan.VkCommandBuffer cmd = Renderer.getCommandBuffer();
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                depthImage.transitionImageLayout(stack, cmd,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Failed to transition depth for sampling", e);
        }
    }

    /**
     * Transition MC's depth image back to DEPTH_STENCIL_ATTACHMENT_OPTIMAL
     * after sampling is complete, so MC can use it as a depth attachment again.
     * <p>
     * Must be called AFTER the depth-reading render pass has ended and
     * BEFORE rebindMainTarget() starts a new render pass with the depth
     * as an attachment.
     */
    public static void transitionDepthForAttachment(VulkanImage depthImage) {
        try {
            org.lwjgl.vulkan.VkCommandBuffer cmd = Renderer.getCommandBuffer();
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                depthImage.transitionImageLayout(stack, cmd,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Failed to transition depth for attachment", e);
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

    // ========================= //
    // Cloud rendering helpers   //
    // ========================= //

    /**
     * Load a resource by path from Minecraft's resource manager.
     * ResourceLocation was renamed to Identifier in MC 1.21.11.
     */
    public static java.io.InputStream openMcResource(String path) throws java.io.IOException {
        #if MC_VER <= MC_1_20_6
        var loc = new net.minecraft.resources.ResourceLocation(path);
        #elif MC_VER <= MC_1_21_10
        var loc = net.minecraft.resources.ResourceLocation.withDefaultNamespace(path);
        #else
        var loc = net.minecraft.resources.Identifier.withDefaultNamespace(path);
        #endif
        return net.minecraft.client.Minecraft.getInstance().getResourceManager()
                .getResourceOrThrow(loc).open();
    }

    /**
     * Get the cloud render range in blocks.
     * MC 1.21.x has options.cloudRange(), MC 1.20.x uses renderDistance.
     */
    public static int getCloudRenderRange() {
        #if MC_VER <= MC_1_20_6
        return net.minecraft.client.Minecraft.getInstance().options.renderDistance().get() * 16;
        #else
        return Math.min(net.minecraft.client.Minecraft.getInstance().options.cloudRange().get(), 128) * 16;
        #endif
    }

    /**
     * Get cloud pixel data from a NativeImage as int array.
     * MC 1.21.x has getPixelsABGR(), MC 1.20.x has getPixelRGBA per-pixel.
     */
    public static int[] getCloudPixels(com.mojang.blaze3d.platform.NativeImage image) {
        #if MC_VER <= MC_1_20_6
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = image.getPixelRGBA(x, y);
            }
        }
        return pixels;
        #else
        return image.getPixelsABGR();
        #endif
    }

    /**
     * Get cloud height for the current dimension. Returns -1 if none (e.g. nether).
     */
    public static int getCloudHeight(net.minecraft.client.multiplayer.ClientLevel level) {
        try {
            #if MC_VER <= MC_1_20_6
            // MC 1.20.x: cloudHeight might be an OptionalInt
            java.lang.reflect.Method m = level.dimensionType().getClass().getMethod("cloudHeight");
            Object result = m.invoke(level.dimensionType());
            if (result instanceof java.util.OptionalInt) {
                return ((java.util.OptionalInt) result).orElse(-1);
            } else if (result instanceof java.util.Optional) {
                @SuppressWarnings("unchecked")
                java.util.Optional<Integer> opt = (java.util.Optional<Integer>) result;
                return opt.orElse(-1);
            }
            return 192; // fallback overworld
            #elif MC_VER <= MC_1_21_10
            java.util.Optional<Integer> opt = level.dimensionType().cloudHeight();
            return opt.orElse(-1);
            #else
            // MC 1.21.11: dimensionType API may have changed, fallback to 192
            // Cloud height is typically 192 in overworld dimensions
            return level.dimensionType().hasSkyLight() ? 192 : -1;
            #endif
        } catch (Exception e) {
            return 192;
        }
    }

    /**
     * Get cloud color as RGB float[3] for the current level.
     * API varies significantly across MC versions.
     */
    public static float[] getCloudColorRGB(net.minecraft.client.multiplayer.ClientLevel level, float partialTicks) {
        try {
            #if MC_VER < MC_1_21_3
            // MC 1.20.x - 1.21.1: returns Vec3
            net.minecraft.world.phys.Vec3 color = level.getCloudColor(partialTicks);
            return new float[]{(float) color.x, (float) color.y, (float) color.z};
            #elif MC_VER <= MC_1_21_10
            // MC 1.21.3 - 1.21.10: returns int (ARGB)
            int argb = level.getCloudColor(partialTicks);
            float r = ((argb >> 16) & 0xFF) / 255.0f;
            float g = ((argb >> 8) & 0xFF) / 255.0f;
            float b = (argb & 0xFF) / 255.0f;
            return new float[]{r, g, b};
            #else
            // MC 1.21.11+: uses environmentAttributes
            int argb = level.environmentAttributes().getValue(
                    net.minecraft.world.attribute.EnvironmentAttributes.CLOUD_COLOR,
                    net.minecraft.core.BlockPos.ZERO);
            float r = ((argb >> 16) & 0xFF) / 255.0f;
            float g = ((argb >> 8) & 0xFF) / 255.0f;
            float b = (argb & 0xFF) / 255.0f;
            return new float[]{r, g, b};
            #endif
        } catch (Exception e) {
            return new float[]{1.0f, 1.0f, 1.0f}; // fallback white
        }
    }

    /**
     * Begin building cloud mesh geometry.
     * MC 1.21.x: Tesselator.getInstance().begin(Mode, Format)
     * MC 1.20.x: new BufferBuilder(size); builder.begin(Mode, Format)
     */
    public static com.mojang.blaze3d.vertex.BufferBuilder beginCloudMesh() {
        #if MC_VER <= MC_1_20_6
        com.mojang.blaze3d.vertex.BufferBuilder builder = com.mojang.blaze3d.vertex.Tesselator.getInstance().getBuilder();
        builder.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
        return builder;
        #else
        return com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
        #endif
    }

    /**
     * Add a vertex with position + ARGB color to the cloud mesh builder.
     */
    public static void putCloudVertex(com.mojang.blaze3d.vertex.BufferBuilder builder, float x, float y, float z, int color) {
        #if MC_VER <= MC_1_20_6
        float a = ((color >> 24) & 0xFF) / 255.0f;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        builder.vertex(x, y, z).color(r, g, b, a).endVertex();
        #else
        builder.addVertex(x, y, z).setColor(color);
        #endif
    }

    /**
     * Finish building the cloud mesh, returning the mesh data (or null if empty).
     * Returns Object to avoid referencing MeshData which doesn't exist on 1.20.x.
     */
    public static Object finishCloudMesh(com.mojang.blaze3d.vertex.BufferBuilder builder) {
        #if MC_VER <= MC_1_20_6
        // On 1.20.x, builder.end() returns BufferBuilder.RenderedBuffer
        // which is what VM 0.4.2's VBO.upload() expects
        return builder.end();
        #else
        return builder.build();
        #endif
    }

    /**
     * Set VRenderSystem model offset. Only exists on VM 0.6.x.
     */
    public static void setModelOffset(float x, float y, float z) {
        try {
            java.lang.reflect.Method m = net.vulkanmod.vulkan.VRenderSystem.class.getMethod("setModelOffset", float.class, float.class, float.class);
            m.invoke(null, x, y, z);
        } catch (Exception ignored) {
            // VM 0.4.2 doesn't have setModelOffset — no-op
        }
    }

    /**
     * Get the MeshData class for reflection. Returns null on MC 1.20.x (class doesn't exist).
     */
    public static Class<?> getMeshDataClass() {
        try {
            return Class.forName("com.mojang.blaze3d.vertex.MeshData");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static final com.seibel.distanthorizons.core.logging.DhLogger LOGGER = new com.seibel.distanthorizons.core.logging.DhLoggerBuilder()
            .build();

    private Compat() {
    }
}
