/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    VulkanMod rendering delegate implementation.
 */

package com.seibel.distanthorizons.fabric.vulkan;

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.core.render.renderer.IVulkanRenderDelegate;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;

import java.nio.ByteBuffer;

/**
 * Concrete implementation of {@link IVulkanRenderDelegate} that uses
 * VulkanMod's rendering API to draw DH terrain.
 * <p>
 * This class bridges the gap between DH's core rendering loop (which can't
 * import VulkanMod) and the VulkanMod API (only available in the fabric
 * module).
 */
public class VulkanRenderDelegate implements IVulkanRenderDelegate {
    private static final DhLogger LOGGER = new DhLoggerBuilder().build();

    private final VulkanRenderContext renderContext;
    private boolean initialized = false;
    private boolean initFailed = false;

    /** Shared index buffer for quad rendering (6 indices per quad) */
    private IndexBuffer quadIndexBuffer;

    public VulkanRenderDelegate() {
        this.renderContext = VulkanRenderContext.getInstance();
    }

    @Override
    public void init() {
        if (this.initialized || this.initFailed) {
            return;
        }

        LOGGER.info("[DH-Vulkan] VulkanRenderDelegate initializing...");
        try {
            this.renderContext.init();

            // Create a shared quad index buffer
            // DH uses quads rendered as indexed triangles: 4 vertices -> 6 indices per quad
            int initialQuadCount = 65536;
            int indexCount = initialQuadCount * 6;
            ByteBuffer indexData = ByteBuffer.allocateDirect(indexCount * 4); // UINT32
            indexData.order(java.nio.ByteOrder.nativeOrder());
            for (int i = 0; i < initialQuadCount; i++) {
                int base = i * 4;
                indexData.putInt(base + 0);
                indexData.putInt(base + 1);
                indexData.putInt(base + 2);
                indexData.putInt(base + 2);
                indexData.putInt(base + 3);
                indexData.putInt(base + 0);
            }
            indexData.flip();

            this.quadIndexBuffer = VulkanRenderContext.createIndexBuffer(indexData.remaining());
            this.quadIndexBuffer.copyBuffer(indexData, indexData.remaining());

            this.initialized = true;
            LOGGER.info("[DH-Vulkan] VulkanRenderDelegate initialized. Quad IBO: {} indices", indexCount);
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] VulkanRenderDelegate init failed — LODs will not render", e);
            this.initFailed = true;
        }
    }

    @Override
    public void beginFrame() {
        if (!this.initialized) {
            this.init();
        }
        if (this.initFailed) {
            return;
        }

        this.renderContext.bindTerrainPipeline();
    }

    @Override
    public long uploadVertexData(ByteBuffer vertexData, int vertexCount) {
        // Create a vertex buffer and upload data
        // In a future optimization, we could pool/reuse these buffers
        int dataSize = vertexData.remaining();
        net.vulkanmod.vulkan.memory.buffer.VertexBuffer vkVertexBuffer = VulkanRenderContext
                .createVertexBuffer(dataSize);
        vkVertexBuffer.copyBuffer(vertexData, dataSize);
        // Return a handle (for now, the buffer's native handle)
        return vkVertexBuffer.getId();
    }

    @Override
    public void drawBuffer(GLVertexBuffer vbo, int indexCount) {
        // The GLVertexBuffer holds the vertex data that was uploaded via GL.
        // Under VulkanMod, we need the data to be in a Vulkan buffer instead.
        // For now, we log that we'd draw here - the actual data path
        // (VBO data → Vulkan buffer) needs the buffer building pipeline to be adapted.

        // TODO: In the full implementation, GLVertexBuffer will be replaced with a
        // VulkanVertexBuffer that holds a reference to the Vulkan Buffer directly.
        // For now, this is a no-op placeholder that proves the wiring works.

        // The draw call would be:
        // this.renderContext.drawIndexed(vulkanVertexBuffer, this.quadIndexBuffer,
        // indexCount);
    }

    @Override
    public void endFrame() {
        // Nothing to do — VulkanMod manages frame synchronization
    }

    @Override
    public void cleanup() {
        if (this.quadIndexBuffer != null) {
            this.quadIndexBuffer.scheduleFree();
            this.quadIndexBuffer = null;
        }
        this.renderContext.cleanup();
        LOGGER.info("[DH-Vulkan] VulkanRenderDelegate cleaned up.");
    }
}
