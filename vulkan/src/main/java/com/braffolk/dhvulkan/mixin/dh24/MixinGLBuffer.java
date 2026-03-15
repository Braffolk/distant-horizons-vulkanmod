package com.braffolk.dhvulkan.mixin.dh24;

import com.braffolk.dhvulkan.dh24.IVulkanRenderDelegate;
import com.braffolk.dhvulkan.compat.Compat;
import com.braffolk.dhvulkan.dh24.duck.IVulkanLodRenderer;
import com.braffolk.dhvulkan.dh24.duck.IVulkanVertexBuffer;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLBuffer;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
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
        if (Compat.isVulkanModActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "bind", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$skipBind(CallbackInfo ci) {
        if (Compat.isVulkanModActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "unbind", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$skipUnbind(CallbackInfo ci) {
        if (Compat.isVulkanModActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "uploadBuffer", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$skipGLUpload(ByteBuffer bb, EDhApiGpuUploadMethod uploadMethod,
            int maxExpansionSize, int bufferHint, CallbackInfo ci) {
        if (Compat.isVulkanModActive()) {
            // Data is handled by MixinLodBufferContainer at a higher level.
            // Just prevent the GL upload from running.
            this.size = bb.remaining();
            ci.cancel();
        }
    }

    @Inject(method = "destroyAsync", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$virtualDestroy(CallbackInfo ci) {
        if (Compat.isVulkanModActive()) {
            // Free the cached Vulkan GPU buffer from the delegate's cache.
            // Without this, the GPU buffer leaks forever when DH unloads LOD sections.
            dhvulkan$freeVulkanCacheEntry();

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
        if (Compat.isVulkanModActive()) {
            // Free the cached Vulkan GPU buffer from the delegate's cache.
            dhvulkan$freeVulkanCacheEntry();

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

    /**
     * Queue this VBO for deferred GPU buffer free on the render thread.
     * Called from destroyAsync()/close() on DH worker threads.
     * The actual cache removal and scheduleFree happen in beginFrame()
     * on the render thread, preventing race conditions with drawBuffer().
     */
    @Unique
    private void dhvulkan$freeVulkanCacheEntry() {
        if (!((Object) this instanceof GLVertexBuffer)) {
            return;
        }
        try {
            IVulkanLodRenderer lodRenderer = (IVulkanLodRenderer) LodRenderer.INSTANCE;
            IVulkanRenderDelegate delegate = lodRenderer.dhvulkan$getVulkanDelegate();
            if (delegate != null) {
                delegate.queueBufferFree((GLVertexBuffer) (Object) this);
            }
        } catch (Exception e) {
            // Silently ignore — delegate may not be wired yet during early init,
            // or LodRenderer.INSTANCE may be null during shutdown.
        }
    }
}
