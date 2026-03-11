package com.braffolk.dhvulkan.mixin.shared;

import com.braffolk.dhvulkan.compat.Compat;
import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into DH's LightMapWrapper to skip all GL calls when VulkanMod is
 * active.
 *
 * uploadLightmap() calls GL11.glIsTexture(), createLightmap() calls
 * glTexImage2D(),
 * bind()/unbind() call glBindTexture()/glActiveTexture() — all fatal in Vulkan
 * context.
 *
 * For now, these methods are no-op'd in Vulkan. Lightmap-based LOD coloring for
 * MC 1.20.6 requires a separate Vulkan lightmap pipeline (not yet implemented).
 */
@Mixin(targets = "loaderCommon.fabric.com.seibel.distanthorizons.common.wrappers.misc.LightMapWrapper", remap = false)
public class MixinLightMapWrapper {

    @Inject(method = "uploadLightmap", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$skipUploadLightmap(NativeImage nativeImage, CallbackInfo ci) {
        if (Compat.isVulkanModActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "createLightmap", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$skipCreateLightmap(NativeImage nativeImage, CallbackInfo ci) {
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
}
