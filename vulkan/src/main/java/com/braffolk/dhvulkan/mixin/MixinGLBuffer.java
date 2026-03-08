package com.braffolk.dhvulkan.mixin;

import com.braffolk.dhvulkan.duck.IVulkanGLProxy;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;

/**
 * Mixin into {@link GLBuffer} to virtualize GL buffer operations when VulkanMod
 * is active.
 * Instead of calling glBindBuffer/glBufferData, we store the raw ByteBuffer
 * data for the
 * Vulkan renderer to consume at draw time. This transparently handles
 * {@code LodBufferContainer.uploadVertexData()} without needing to modify it.
 */
@Mixin(value = GLBuffer.class, remap = false)
public abstract class MixinGLBuffer {

    @Shadow
    protected int size;

    /** Raw vertex data stored for the Vulkan renderer */
    @Unique
    private ByteBuffer dhvulkan$storedData = null;

    @Inject(method = "bind", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$skipBind(CallbackInfo ci) {
        if (IVulkanGLProxy.isVulkanModActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "unbind", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$skipUnbind(CallbackInfo ci) {
        if (IVulkanGLProxy.isVulkanModActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "uploadBuffer", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$virtualUpload(ByteBuffer bb, EDhApiGpuUploadMethod uploadMethod,
            int maxExpansionSize, int bufferHint, CallbackInfo ci) {
        if (IVulkanGLProxy.isVulkanModActive()) {
            int dataSize = bb.remaining();
            if (dataSize > 0) {
                // Free old stored data
                if (this.dhvulkan$storedData != null) {
                    org.lwjgl.system.MemoryUtil.memFree(this.dhvulkan$storedData);
                }
                // Copy the vertex data into a direct ByteBuffer
                ByteBuffer copy = org.lwjgl.system.MemoryUtil.memAlloc(dataSize);
                copy.put(bb.duplicate());
                copy.flip();
                this.dhvulkan$storedData = copy;
                this.size = dataSize;
            }
            ci.cancel();
        }
    }

    @Inject(method = "destroyAsync", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$virtualDestroy(CallbackInfo ci) {
        if (IVulkanGLProxy.isVulkanModActive()) {
            if (this.dhvulkan$storedData != null) {
                org.lwjgl.system.MemoryUtil.memFree(this.dhvulkan$storedData);
                this.dhvulkan$storedData = null;
            }
            this.size = 0;
            ci.cancel();
        }
    }
}
