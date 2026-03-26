package com.braffolk.dhvulkan.mixin.shared;

import com.braffolk.dhvulkan.compat.Compat;
import com.seibel.distanthorizons.core.config.Config;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into Minecraft's {@link LevelRenderer}.
 *
 * Phase 2 deferred composite runs at renderLevel @RETURN (after weather).
 * The composite uses GL_LEQUAL depth test so it won't overwrite weather pixels
 * (weather writes depth < 1.0, Phase 2 writes gl_FragDepth = 1.0 at open-sky
 * LODs, so LEQUAL fails and weather is preserved).
 *
 * Cloud cancellation hooks at addCloudsPass/renderClouds (require=0).
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    /**
     * Cancel vanilla clouds when DH overrides them (1.20.6 hook).
     */
    @Inject(method = "addCloudsPass", at = @At("HEAD"), cancellable = true, require = 0)
    private void dhvulkan$cancelVanillaClouds120(CallbackInfo ci) {
        if (!Compat.isVulkanModActive()) return;
        try {
            if (Config.Client.Advanced.Graphics.overrideVanillaGraphicsSettings.get()) {
                ci.cancel();
            }
        } catch (Exception e) {}
    }

    /**
     * Cancel vanilla clouds when DH overrides them (1.21.11 hook).
     */
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true, require = 0)
    private void dhvulkan$cancelVanillaClouds121(CallbackInfo ci) {
        if (!Compat.isVulkanModActive()) return;
        try {
            if (Config.Client.Advanced.Graphics.overrideVanillaGraphicsSettings.get()) {
                ci.cancel();
            }
        } catch (Exception e) {}
    }

    /**
     * Phase 2: deferred composite at renderLevel @RETURN.
     * Fires AFTER terrain + weather + everything.
     * Uses GL_LEQUAL depth test so weather pixels (depth < 1.0) are preserved —
     * the composite writes gl_FragDepth = 1.0 at open-sky LODs, which fails
     * LEQUAL against weather's depth.
     */
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void dhvulkan$lateComposite(CallbackInfo ci) {
        if (!Compat.isVulkanModActive()) return;
        Compat.runLateCompositeHook();
    }

}
