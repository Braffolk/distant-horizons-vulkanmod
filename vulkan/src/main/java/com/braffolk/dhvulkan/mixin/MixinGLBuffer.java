package com.braffolk.dhvulkan.mixin;

import com.braffolk.dhvulkan.duck.IVulkanGLProxy;
import com.braffolk.dhvulkan.duck.IVulkanVertexBuffer;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;

/**
 * Mixin into {@link GLBuffer} to virtualize GL buffer operations when VulkanMod
 * is active.
 *
 * Instead of calling glBindBuffer/glBufferData/glGenBuffers, we store the raw
 * ByteBuffer data on the VBO via {@link IVulkanVertexBuffer} duck interface.
 * The Vulkan renderer reads this data at draw time to create GPU-side buffers.
 *
 * This intercepts ALL GL touchpoints in GLBuffer's lifecycle:
 * create, bind, unbind, uploadBuffer, destroyAsync, close
 */
@Mixin(value = GLBuffer.class, remap = false)
public abstract class MixinGLBuffer {

    @Shadow
    protected int size;

    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$skipCreate(boolean asBufferStorage, CallbackInfo ci) {
        if (IVulkanGLProxy.isVulkanModActive()) {
            ci.cancel();
        }
    }

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
    private void dhvulkan$skipGLUpload(ByteBuffer bb, EDhApiGpuUploadMethod uploadMethod,
            int maxExpansionSize, int bufferHint, CallbackInfo ci) {
        if (IVulkanGLProxy.isVulkanModActive()) {
            // Data is handled by MixinLodBufferContainer at a higher level.
            // Just prevent the GL upload from running.
            this.size = bb.remaining();
            ci.cancel();
        }
    }

    @Inject(method = "destroyAsync", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$virtualDestroy(CallbackInfo ci) {
        if (IVulkanGLProxy.isVulkanModActive()) {
            if (this instanceof IVulkanVertexBuffer) {
                IVulkanVertexBuffer vkBuf = (IVulkanVertexBuffer) this;
                Object handle = vkBuf.dhvulkan$getVulkanBufferHandle();
                if (handle instanceof ByteBuffer) {
                    org.lwjgl.system.MemoryUtil.memFree((ByteBuffer) handle);
                }
                vkBuf.dhvulkan$setVulkanBufferHandle(null);
                vkBuf.dhvulkan$setVulkanBufferByteSize(0);
            }
            this.size = 0;
            ci.cancel();
        }
    }

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$skipClose(CallbackInfo ci) {
        if (IVulkanGLProxy.isVulkanModActive()) {
            if (this instanceof IVulkanVertexBuffer) {
                IVulkanVertexBuffer vkBuf = (IVulkanVertexBuffer) this;
                Object handle = vkBuf.dhvulkan$getVulkanBufferHandle();
                if (handle instanceof ByteBuffer) {
                    org.lwjgl.system.MemoryUtil.memFree((ByteBuffer) handle);
                }
                vkBuf.dhvulkan$setVulkanBufferHandle(null);
                vkBuf.dhvulkan$setVulkanBufferByteSize(0);
            }
            this.size = 0;
            ci.cancel();
        }
    }
}
