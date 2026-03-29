package com.braffolk.dhvulkan.mixin.beryl;

import com.braffolk.dhvulkan.core.shadow.DhBerylShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects DH LOD shadow rendering into Beryl's shadow pass.
 * <p>
 * Targets {@code RenderingPipeline.beginRender()}, injecting AFTER
 * {@code shadowMap.renderShadowMap()} while the shadow render pass
 * is still active. DH LODs are drawn into the same shadow framebuffer.
 * <p>
 * This mixin is conditionally applied — only when Beryl is present
 * (controlled by {@code DhVulkanMixinPlugin.shouldApplyMixin()}).
 */
@Mixin(targets = "net.beryl.render.RenderingPipeline", remap = false)
public abstract class MixinBerylRenderingPipeline {

    /**
     * After Beryl renders MC terrain shadows, inject DH LOD shadows
     * into the same shadow map. The shadow render pass is still active
     * at this injection point.
     */
    @Inject(
            method = "beginRender",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/beryl/render/ShadowMap;renderShadowMap",
                    shift = At.Shift.AFTER
            ),
            require = 0  // graceful no-op if Beryl API changes
    )
    private static void dhvulkan$renderLodShadows(CallbackInfo ci) {
        DhBerylShadowRenderer.renderLodShadows();
    }
}
