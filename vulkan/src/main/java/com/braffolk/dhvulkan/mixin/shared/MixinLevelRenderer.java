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
     * Register DH's lightmap wrapper at the START of level rendering, BEFORE DH's own
     * LOD render (and thus before ClientApi's RenderParams validation) fires later in
     * this same renderLevel call.
     *
     * DH 3.2.0-b hard-gates LOD rendering: ClientApi skips rendering entirely when
     * RenderParams.getValidationErrorMessage() != null, and it returns "No Lightmap Loaded"
     * unless MinecraftRenderWrapper.getLightmapWrapper(level) is non-null. That wrapper is
     * normally registered by DH's MixinLightTexture → updateLightmap(), which does not
     * produce a registered wrapper under VulkanMod (VM overwrites/replaces MC's LightTexture
     * drive). Registering from inside our render path is too late — the gate skips that path,
     * so it never runs (chicken-and-egg). Doing it here (ungated, once per frame) opens the
     * gate; the actual lightmap texture is still supplied to the Vulkan renderer separately
     * via Compat.getLightmapVulkanImage(). GL-free (setLightmapId is a pure setter).
     */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void dhvulkan$registerLightmapWrapper(CallbackInfo ci) {
        if (!Compat.isVulkanModActive()) return;
        Compat.ensureDhLightmapWrapperRegistered();
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
