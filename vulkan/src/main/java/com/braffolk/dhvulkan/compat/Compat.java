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

import java.nio.ByteBuffer;

/**
 * Single source of truth for ALL version-specific API differences.
 * NO other file should contain #if blocks.
 */
public final class Compat {

    // ========================= //
    // Buffer factories //
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
        // id must be 0 for non-UV usages. Only UV allows multiple indices.
        // Remap GENERIC → UV so we can use id as the UV index.
        if (usage == VertexFormatElement.Usage.GENERIC) {
            return new VertexFormatElement(id, type, VertexFormatElement.Usage.UV, count);
        }
        // For non-UV usages (POSITION, COLOR, NORMAL), id must be 0
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
            java.lang.reflect.Field cmdField = Renderer.class.getDeclaredField("currentCmdBuffer");
            cmdField.setAccessible(true);
            org.lwjgl.vulkan.VkCommandBuffer cmdBuffer =
                    (org.lwjgl.vulkan.VkCommandBuffer) cmdField.get(Renderer.getInstance());
            org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush();
            framebuffer.beginRenderPass(cmdBuffer, renderPass, stack);
            stack.close();
        } catch (Exception e) {
            throw new RuntimeException("[DH-Vulkan] Failed to begin render pass on VM 0.4.2", e);
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
            // VM 0.4.2 doesn't have VkGlTexture bridge — lightmap not available yet
            return null;
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

    private Compat() {
    }
}
