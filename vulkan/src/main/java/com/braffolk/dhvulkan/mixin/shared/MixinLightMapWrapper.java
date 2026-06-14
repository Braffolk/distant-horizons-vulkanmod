package com.braffolk.dhvulkan.mixin.shared;

import com.braffolk.dhvulkan.compat.Compat;
import com.mojang.blaze3d.platform.NativeImage;
import com.seibel.distanthorizons.common.wrappers.misc.LightMapWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into DH's LightMapWrapper to skip GL / legacy upload paths when VulkanMod is active.
 *
 * DH 3.0.3 (26.1): class is {@code com.seibel.distanthorizons.common.wrappers.misc.LightMapWrapper}.
 * {@code uploadLightmap()} throws on 26.1+ (MC uses {@code setLightmapGpuTexture} instead).
 * Our engine binds MC's lightmap via {@link Compat#getLightmapVulkanImage()}.
 */
@Mixin(value = LightMapWrapper.class, remap = false)
public class MixinLightMapWrapper {

    @Inject(method = "uploadLightmap", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$skipUploadLightmap(NativeImage nativeImage, CallbackInfo ci) {
        if (Compat.isVulkanModActive()) {
            ci.cancel();
        }
    }

    /** DH 2.4 only — absent in DH 3.0.3 */
    @Inject(method = "createLightmap", at = @At("HEAD"), cancellable = true, require = 0)
    private void dhvulkan$skipCreateLightmap(NativeImage nativeImage, CallbackInfo ci) {
        if (Compat.isVulkanModActive()) {
            ci.cancel();
        }
    }

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
