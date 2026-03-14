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
 * Cancels VulkanMod's vanilla cloud rendering when DH's
 * "Override vanilla graphics settings" is enabled.
 * Our own cloud rendering happens after the DH composite
 * in VulkanRenderEngine, which ensures correct depth testing
 * against both MC terrain and DH LODs.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    /**
     * Cancel cloud rendering when DH+VulkanMod are active and
     * DH's override config says to replace vanilla clouds.
     *
     * This prevents VM's cloud pass from running. Our own clouds
     * are rendered by VulkanCloudRenderer after the DH composite,
     * controlled by DH's enableCloudRendering config.
     */
    @Inject(method = "addCloudsPass", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$cancelVanillaClouds(CallbackInfo ci) {
        if (!Compat.isVulkanModActive()) return;

        try {
            if (Config.Client.Advanced.Graphics.overrideVanillaGraphicsSettings.get()) {
                ci.cancel();
            }
        } catch (Exception e) {
            // Config not yet available — let vanilla clouds render
        }
    }
}
