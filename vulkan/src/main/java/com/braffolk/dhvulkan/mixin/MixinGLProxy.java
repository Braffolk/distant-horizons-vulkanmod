package com.braffolk.dhvulkan.mixin;

import com.braffolk.dhvulkan.duck.IVulkanGLProxy;
import com.seibel.distanthorizons.core.render.glObject.GLProxy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into {@link GLProxy} to skip OpenGL context initialization when
 * VulkanMod is active.
 * VulkanMod replaces MC's OpenGL context with Vulkan, so all GL calls would
 * crash.
 */
@Mixin(value = GLProxy.class, remap = false)
public class MixinGLProxy {

    /**
     * Skip the entire constructor body when VulkanMod is active.
     * The constructor tries to get GL capabilities, check GL versions, etc.
     * — none of which is possible without an GL context.
     */
    @Inject(method = "<init>", at = @At("HEAD"), cancellable = true)
    private void dhvulkan$skipGLInit(CallbackInfo ci) {
        if (IVulkanGLProxy.isVulkanModActive()) {
            GLProxy.LOGGER.info("VulkanMod detected. Skipping OpenGL context setup — using Vulkan rendering backend.");
            GLProxy.LOGGER.info(GLProxy.class.getSimpleName() + " creation successful (VulkanMod mode).");
            ci.cancel();
        }
    }
}
