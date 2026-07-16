package com.braffolk.dhvulkan.mixin.dh3;

import com.braffolk.dhvulkan.compat.Compat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts DH 3.0's DependencySetup.setRenderingApiBindings() to replace
 * the default GL/Blaze3D renderer with our Vulkan renderer.
 *
 * Without this, DH's delayed setup overwrites our early bindRenderers() call.
 * By cancelling the original method and binding our VkRenderApiDefinition instead,
 * we ensure DH uses our Vulkan rendering pipeline.
 */
// DH 3.2.0-b dropped the `loaderCommon.fabric.` relocation prefix and now emits
// per-loader classes with a `_fabric` / `_neoforge` suffix. Under Fabric the class
// actually instantiated (and whose static setRenderingApiBindings() runs) is the
// _fabric variant. See HANDOFF Open Issue #1.
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.DependencySetup_fabric", remap = false)
public class MixinDependencySetup {

    @Inject(method = "setRenderingApiBindings", at = @At("HEAD"), cancellable = true)
    private static void dhvulkan$overrideRenderApi(CallbackInfo ci) {
        if (!Compat.isVulkanModActive()) return;

        System.out.println("[DH-VulkanMod] Intercepting DH 3.0 renderer binding — injecting Vulkan render API.");

        // Get the VkRenderApiDefinition from ApiDhIntegration and bind it
        com.braffolk.dhvulkan.api.ApiDhIntegration integration =
            com.braffolk.dhvulkan.api.ApiDhIntegration.getInstance();
        if (integration != null) {
            integration.bindRenderApi();
            ci.cancel();
        } else {
            System.out.println("[DH-VulkanMod] WARNING: ApiDhIntegration not initialized yet, falling back to default renderer.");
        }
    }
}
