package com.braffolk.dhvulkan.mixin;

import com.braffolk.dhvulkan.duck.IVulkanGLProxy;
import com.seibel.distanthorizons.core.render.glObject.GLState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into {@link GLState} to skip GL state save/restore when VulkanMod is
 * active.
 * {@code saveState()} calls dozens of {@code glGetInteger()} functions that
 * crash without GL.
 */
@Mixin(value = GLState.class, remap = false)
public class MixinGLState {

    @Inject(method = "saveState", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$skipSaveState(CallbackInfo ci) {
        if (IVulkanGLProxy.isVulkanModActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$skipClose(CallbackInfo ci) {
        if (IVulkanGLProxy.isVulkanModActive()) {
            ci.cancel();
        }
    }
}
