/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 */

package com.seibel.distanthorizons.core.render.renderer;

import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;

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

    /** Upload vertex data to a Vulkan buffer and return a handle for drawing */
    long uploadVertexData(ByteBuffer vertexData, int vertexCount);

    /**
     * Draw a vertex buffer. Called per-VBO in the render loop.
     * 
     * @param vbo        the GL vertex buffer (used for vertex count and data)
     * @param indexCount number of indices to draw
     */
    void drawBuffer(GLVertexBuffer vbo, int indexCount);

    /** End the current frame's rendering */
    void endFrame();

    /** Clean up all Vulkan resources */
    void cleanup();
}
