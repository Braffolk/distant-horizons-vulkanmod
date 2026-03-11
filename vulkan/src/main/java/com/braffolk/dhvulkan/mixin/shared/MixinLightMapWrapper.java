package com.braffolk.dhvulkan.mixin.shared;

import com.braffolk.dhvulkan.compat.Compat;
import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into DH's LightMapWrapper to skip all GL calls when VulkanMod is
 * active. Applies to both DH 2.4 and DH 3.0.
 *
 * uploadLightmap() and createLightmap() exist in both versions and make GL
 * calls (glIsTexture, glTexImage2D, glBindTexture) that are fatal in Vulkan.
 *
 * bind()/unbind() only exist in DH 2.4 (they call glBindTexture/glActiveTexture)
 * — marked require = 0 so the mixin doesn't fail when targeting DH 3.0.
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

    // bind/unbind only exist in DH 2.4 — require = 0 makes these optional
    @Inject(method = "bind", at = @At("HEAD"), cancellable = true, require = 0)
    private void dhvulkan$skipBind(CallbackInfo ci) {
        if (Compat.isVulkanModActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "unbind", at = @At("HEAD"), cancellable = true, require = 0)
    private void dhvulkan$skipUnbind(CallbackInfo ci) {
        if (Compat.isVulkanModActive()) {
            ci.cancel();
        }
    }
}
