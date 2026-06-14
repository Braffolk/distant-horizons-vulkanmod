package com.braffolk.dhvulkan.mixin.beryl;

import com.braffolk.dhvulkan.beryl.BerylCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into Beryl's VulkanMixin (hooks into VulkanMod's Renderer).
 * Ensures DH resources remain consistent across Beryl's render pass transitions.
 */
@Mixin(value = net.beryl.mixin.vkmod.VulkanMixin.class, remap = false)
public class MixinBerylVulkan {

    @Inject(
            method = "endRenderPass",
            at = @At("RETURN"),
            require = 0,
            expect = 0
    )
    private void dhvulkan$afterBerylEndRenderPass(CallbackInfo ci) {
        if (!BerylCompat.shouldUseVulkanWithBeryl()) return;
        // DH depth samplers may need rebinding after Beryl transitions between passes.
        // Actual rebinding handled by MixinShaderMainPass at pass boundaries.
    }
}
