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
 * DH 3.0.3 (26.1) uses {@code com.seibel.distanthorizons.common.wrappers.DependencySetup}.
 * Older mixin targets ({@code loaderCommon.fabric...}) never applied, so DH kept
 * binding GlDhRenderApiDefinition and LODs never rendered on VulkanMod.
 */
@Mixin(value = com.seibel.distanthorizons.common.wrappers.DependencySetup.class, remap = false)
public class MixinDependencySetup {

    @Inject(method = "setRenderingApiBindings", at = @At("HEAD"), cancellable = true)
    private static void dhvulkan$overrideRenderApi(CallbackInfo ci) {
        if (!Compat.isVulkanModActive()) return;

        com.braffolk.dhvulkan.api.ApiDhIntegration integration =
                com.braffolk.dhvulkan.api.ApiDhIntegration.getInstance();
        if (integration == null) {
            // DH can call this before our ClientModInitializer (DH loads first as a dependency).
            com.braffolk.dhvulkan.core.VulkanBackend backend =
                    new com.braffolk.dhvulkan.core.VulkanRenderEngine();
            integration = new com.braffolk.dhvulkan.api.ApiDhIntegration();
            integration.initialize(backend);
            com.braffolk.dhvulkan.DhVulkanModEntrypoint.setActiveIntegration(integration);
        }

        integration.bindRenderApi();
        ci.cancel();
    }
}
