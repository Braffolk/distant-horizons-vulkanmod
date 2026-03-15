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
 * At {@code addCloudsPass @HEAD}:
 * 1. Triggers Phase 2 deferred composite via Compat hook — reads MC depth
 *    (terrain has rendered by now) and re-composites DH LODs with per-pixel
 *    depth comparison. Also renders DH clouds after composite.
 * 2. Optionally cancels vanilla cloud rendering when DH overrides it.
 *
 * This hook fires AFTER terrain but BEFORE weather, so the weather fix
 * (from d46c003) is preserved.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Inject(method = "addCloudsPass", at = @At("HEAD"), cancellable = true, require = 0)
    private void dhvulkan$deferredCompositeAndClouds(CallbackInfo ci) {
        if (!Compat.isVulkanModActive()) return;

        // Phase 2a: read MC depth + render clouds
        Compat.runDeferredCompositeHook();

        // Cancel vanilla clouds when DH overrides them
        try {
            if (Config.Client.Advanced.Graphics.overrideVanillaGraphicsSettings.get()) {
                ci.cancel();
            }
        } catch (Exception e) {
            // Config not yet available — let vanilla clouds render
        }
    }

    /**
     * Phase 2b: late re-composite at renderLevel @RETURN.
     * This fires AFTER terrain + weather + everything else in renderLevel.
     * Re-composites DH LODs with real MC depth for SINGLE/DOUBLE fade modes.
     * Same hook point as vm.5's deferredComposite.
     */
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void dhvulkan$lateComposite(CallbackInfo ci) {
        if (!Compat.isVulkanModActive()) return;

        // Phase 2b: re-composite with real MC depth
        Compat.runLateCompositeHook();
    }
}
