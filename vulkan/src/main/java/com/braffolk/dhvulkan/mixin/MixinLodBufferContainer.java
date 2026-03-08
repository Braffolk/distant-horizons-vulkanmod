package com.braffolk.dhvulkan.mixin;

import com.braffolk.dhvulkan.duck.IVulkanGLProxy;
import com.braffolk.dhvulkan.duck.IVulkanVertexBuffer;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into {@link LodBufferContainer} to handle Vulkan-specific buffer
 * cleanup.
 * The MixinGLBuffer handles the upload side transparently, but we need to
 * ensure
 * that Vulkan GPU buffers are freed when a LodBufferContainer is closed.
 */
@Mixin(value = LodBufferContainer.class, remap = false)
public class MixinLodBufferContainer {

    @Shadow
    public GLVertexBuffer[] vbos;
    @Shadow
    public GLVertexBuffer[] vbosTransparent;

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$cleanupVulkanBuffers(CallbackInfo ci) {
        if (!IVulkanGLProxy.isVulkanModActive())
            return;

        // Free Vulkan GPU buffers for all VBOs
        dhvulkan$freeVulkanVbos(this.vbos);
        dhvulkan$freeVulkanVbos(this.vbosTransparent);

        // Don't cancel — let the original close() run for its non-GL cleanup.
        // GLBuffer.destroyAsync is already intercepted by MixinGLBuffer.
    }

    private static void dhvulkan$freeVulkanVbos(GLVertexBuffer[] vbos) {
        if (vbos == null)
            return;
        for (GLVertexBuffer vbo : vbos) {
            if (vbo == null)
                continue;
            IVulkanVertexBuffer vkVbo = (IVulkanVertexBuffer) vbo;
            Object handle = vkVbo.dhvulkan$getVulkanBufferHandle();
            if (handle != null) {
                // VulkanRenderDelegate manages the GPU buffer lifecycle;
                // just clear the reference so it can be GC'd
                vkVbo.dhvulkan$setVulkanBufferHandle(null);
                vkVbo.dhvulkan$setVulkanBufferByteSize(0);
            }
        }
    }
}
