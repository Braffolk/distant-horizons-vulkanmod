package com.braffolk.dhvulkan.dh24.mixin;

import com.braffolk.dhvulkan.dh24.duck.IVulkanVertexBuffer;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mixin into {@link GLVertexBuffer} to add Vulkan buffer handle fields.
 * These are used by the Vulkan renderer to track GPU-side buffers.
 */
@Mixin(value = GLVertexBuffer.class, remap = false)
public class MixinGLVertexBuffer implements IVulkanVertexBuffer {

    @Unique
    private Object dhvulkan$vulkanBufferHandle = null;

    @Unique
    private int dhvulkan$vulkanBufferByteSize = 0;

    @Override
    public Object dhvulkan$getVulkanBufferHandle() {
        return this.dhvulkan$vulkanBufferHandle;
    }

    @Override
    public void dhvulkan$setVulkanBufferHandle(Object handle) {
        this.dhvulkan$vulkanBufferHandle = handle;
    }

    @Override
    public int dhvulkan$getVulkanBufferByteSize() {
        return this.dhvulkan$vulkanBufferByteSize;
    }

    @Override
    public void dhvulkan$setVulkanBufferByteSize(int size) {
        this.dhvulkan$vulkanBufferByteSize = size;
    }
}
