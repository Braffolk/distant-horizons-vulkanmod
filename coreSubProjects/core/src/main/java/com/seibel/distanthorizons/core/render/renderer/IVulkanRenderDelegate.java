/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 */

package com.seibel.distanthorizons.core.render.renderer;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.core.util.math.Vec3f;

import java.nio.ByteBuffer;

/**
 * Abstraction for the Vulkan draw path, implemented in the fabric module
 * where VulkanMod classes are on the classpath.
 * <p>
 * When VulkanMod is active, the LodRenderer delegates draw calls to this
 * interface instead of issuing GL commands directly.
 */
public interface IVulkanRenderDelegate {
    /** Initialize the Vulkan rendering context (pipeline, etc.) */
    void init();

    /** Bind the terrain pipeline and set up render state for a new frame */
    void beginFrame();

    /**
     * Upload per-frame uniform data (camera matrix, fog, noise, etc.)
     * Must be called after beginFrame() and before any drawBuffer() calls.
     */
    void fillUniformData(DhApiRenderParam renderParameters);

    /**
     * Set the per-buffer model offset uniform.
     * Called once per LodBufferContainer, before drawing its VBOs.
     */
    void setModelOffset(Vec3f modelOffset);

    /** Upload vertex data to a Vulkan buffer and return a handle for drawing */
    long uploadVertexData(ByteBuffer vertexData, int vertexCount);

    /**
     * Draw a vertex buffer. Called per-VBO in the render loop.
     * 
     * @param vbo        the GL vertex buffer (used for vertex count and data)
     * @param indexCount number of indices to draw
     */
    void drawBuffer(GLVertexBuffer vbo, int indexCount);

    /**
     * Enable or disable alpha blending for the current render pass.
     * Called before drawing transparent LODs (water, glass).
     */
    void setBlendState(boolean enabled);

    /**
     * End the current frame's rendering.
     * Post-process effects (SSAO, fog) use renderParam for projection matrices.
     */
    void endFrame(DhApiRenderParam renderParam);

    /**
     * Free the cached Vulkan buffer associated with a GLVertexBuffer.
     * Called from LodBufferContainer.close() when DH destroys a VBO.
     * This ensures GPU memory is freed deterministically, not relying on GC.
     */
    void freeBuffer(GLVertexBuffer vbo);

    /** Clean up all Vulkan resources */
    void cleanup();
}
