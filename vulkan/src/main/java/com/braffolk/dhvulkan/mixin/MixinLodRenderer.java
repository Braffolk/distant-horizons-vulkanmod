package com.braffolk.dhvulkan.mixin;

import com.braffolk.dhvulkan.IVulkanRenderDelegate;
import com.braffolk.dhvulkan.duck.IVulkanGLProxy;
import com.braffolk.dhvulkan.duck.IVulkanLodRenderer;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
import com.seibel.distanthorizons.core.render.renderer.RenderParams;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.util.math.Vec3d;
import com.seibel.distanthorizons.core.util.math.Vec3f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tier 1 mixin: Replaces the entire DH render pipeline when VulkanMod is
 * active.
 *
 * Intercepts {@code renderLodPass(RenderParams, IProfilerWrapper, boolean)} at
 * HEAD
 * and redirects all rendering through the Vulkan delegate. DH's GL code never
 * runs.
 */
@Mixin(value = LodRenderer.class, remap = false)
public class MixinLodRenderer implements IVulkanLodRenderer {

    @Unique
    private IVulkanRenderDelegate dhvulkan$vulkanDelegate = null;

    @Unique
    private DhApiRenderParam dhvulkan$lastVulkanRenderParams = null;

    // ---- Duck interface implementation ----

    @Override
    public void dhvulkan$setVulkanDelegate(IVulkanRenderDelegate delegate) {
        this.dhvulkan$vulkanDelegate = delegate;
    }

    @Override
    public IVulkanRenderDelegate dhvulkan$getVulkanDelegate() {
        return this.dhvulkan$vulkanDelegate;
    }

    @Override
    public void dhvulkan$setLastVulkanRenderParams(DhApiRenderParam params) {
        this.dhvulkan$lastVulkanRenderParams = params;
    }

    @Override
    public DhApiRenderParam dhvulkan$getLastVulkanRenderParams() {
        return this.dhvulkan$lastVulkanRenderParams;
    }

    @Override
    public void dhvulkan$compositeVulkanFrame() {
        if (this.dhvulkan$vulkanDelegate != null && this.dhvulkan$lastVulkanRenderParams != null) {
            this.dhvulkan$vulkanDelegate.deferredComposite(this.dhvulkan$lastVulkanRenderParams);
            this.dhvulkan$lastVulkanRenderParams = null;
        }
    }

    // ---- Render pipeline takeover ----

    /**
     * Intercept createRenderObjects() HEAD — skip all GL object creation when
     * Vulkan is active.
     */
    @Inject(method = "createRenderObjects", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$skipGLRenderObjectCreation(CallbackInfoReturnable<Boolean> cir) {
        if (IVulkanGLProxy.isVulkanModActive()) {
            LodRenderer.LOGGER.info("[DH-VulkanMod] Skipping GL render object creation (VulkanMod active)");
            cir.setReturnValue(true); // signal success to caller
        }
    }

    /**
     * Intercept the full renderLodPass(RenderParams, IProfilerWrapper, boolean) at
     * HEAD.
     * When Vulkan is active, skip DH's GL pipeline and run the Vulkan delegate
     * instead.
     */
    @Inject(method = "renderLodPass(Lcom/seibel/distanthorizons/core/render/renderer/RenderParams;Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;Z)V", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$vulkanRenderPass(RenderParams renderParams, IProfilerWrapper profiler,
            boolean runningDeferredPass, CallbackInfo ci) {
        if (!IVulkanGLProxy.isVulkanModActive()) {
            return;
        }

        // Wire the delegate lazily (LodRenderer.INSTANCE doesn't exist during mod init)
        com.braffolk.dhvulkan.DhVulkanModEntrypoint.wireIfNeeded();

        if (this.dhvulkan$vulkanDelegate == null) {
            return; // Let DH's normal GL path run
        }

        // ---- Vulkan render path ----
        profiler.push("LOD Vulkan render");

        // Begin frame and set uniforms
        this.dhvulkan$vulkanDelegate.beginFrame();
        this.dhvulkan$vulkanDelegate.fillUniformData(renderParams);

        // Build render list (culling/sorting)
        RenderBufferHandler renderBufferHandler = renderParams.renderBufferHandler;
        if (!runningDeferredPass) {
            profiler.popPush("LOD build render list");
            renderBufferHandler.buildRenderList(renderParams);
        }

        // Draw opaque LODs
        if (!runningDeferredPass) {
            profiler.popPush("LOD Opaque (Vulkan)");
            this.dhvulkan$vulkanDelegate.setBlendState(false);
            this.dhvulkan$drawAllBuffers(renderBufferHandler, renderParams, true);

            // Draw transparent LODs if not deferred
            boolean deferTransparent = com.seibel.distanthorizons.core.render.DhApiRenderProxy.INSTANCE
                    .getDeferTransparentRendering();
            if (!deferTransparent
                    && com.seibel.distanthorizons.core.config.Config.Client.Advanced.Graphics.Quality.transparency
                            .get().transparencyEnabled) {
                profiler.popPush("LOD Transparent (Vulkan)");
                this.dhvulkan$vulkanDelegate.setBlendState(true);
                this.dhvulkan$drawAllBuffers(renderBufferHandler, renderParams, false);
            }
        } else {
            // Deferred transparent pass
            if (com.seibel.distanthorizons.core.config.Config.Client.Advanced.Graphics.Quality.transparency
                    .get().transparencyEnabled) {
                profiler.popPush("LOD Transparent (Vulkan)");
                this.dhvulkan$vulkanDelegate.setBlendState(true);
                this.dhvulkan$drawAllBuffers(renderBufferHandler, renderParams, false);
            }
        }

        // End frame — delegate handles SSAO, fog, depth compositing
        profiler.popPush("LOD Vulkan cleanup");
        this.dhvulkan$vulkanDelegate.endFrame(renderParams);
        this.dhvulkan$lastVulkanRenderParams = renderParams;

        profiler.pop();
        ci.cancel();
    }

    /**
     * Iterate all LOD buffer containers and draw each VBO through the Vulkan
     * delegate.
     */
    @Unique
    private void dhvulkan$drawAllBuffers(RenderBufferHandler renderBufferHandler,
            RenderParams renderParams, boolean opaquePass) {
        SortedArraySet<LodBufferContainer> containers = renderBufferHandler.getColumnRenderBuffers();
        if (containers == null)
            return;

        Vec3d camPos = renderParams.exactCameraPosition;

        for (int i = 0; i < containers.size(); i++) {
            LodBufferContainer container = containers.get(i);

            // Set model offset (same logic as LodRenderer.setShaderProgramMvmOffset)
            Vec3f modelPos = new Vec3f(
                    (float) (container.minCornerBlockPos.getX() - camPos.x),
                    (float) (container.minCornerBlockPos.getY() - camPos.y),
                    (float) (container.minCornerBlockPos.getZ() - camPos.z));
            this.dhvulkan$vulkanDelegate.setModelOffset(modelPos);

            GLVertexBuffer[] vbos = opaquePass ? container.vbos : container.vbosTransparent;
            for (GLVertexBuffer vbo : vbos) {
                if (vbo == null || vbo.getVertexCount() == 0)
                    continue;
                this.dhvulkan$vulkanDelegate.drawBuffer(vbo, vbo.getVertexCount());
            }
        }
    }
}
