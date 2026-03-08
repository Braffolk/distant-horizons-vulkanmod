package com.braffolk.dhvulkan.duck;

import java.nio.ByteBuffer;

/**
 * Duck interface for {@code GLVertexBuffer}. Implemented via Mixin to add
 * Vulkan buffer handle storage on the unmodified DH class.
 */
public interface IVulkanVertexBuffer {
    /**
     * The raw vertex data ByteBuffer stored for the Vulkan renderer.
     * When VulkanMod is active, uploadBuffer() stores data here instead of calling
     * GL.
     */
    Object dhvulkan$getVulkanBufferHandle();

    void dhvulkan$setVulkanBufferHandle(Object handle);

    int dhvulkan$getVulkanBufferByteSize();

    void dhvulkan$setVulkanBufferByteSize(int size);
}
