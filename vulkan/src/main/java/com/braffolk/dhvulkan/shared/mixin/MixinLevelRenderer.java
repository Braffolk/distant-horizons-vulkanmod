package com.braffolk.dhvulkan.shared.mixin;

import com.braffolk.dhvulkan.compat.Compat;
import com.braffolk.dhvulkan.DhVulkanModEntrypoint;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into Minecraft's {@link LevelRenderer} to composite the Vulkan frame
 * after MC finishes rendering all terrain layers.
 *
 * This hook fires at the end of {@code renderLevel()} so that LODs are
 * composited onto MC's render target with correct depth testing.
 *
 * Shared between DH 2.4 and DH 3.0 paths.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void dhvulkan$compositeAfterMcRender(CallbackInfo ci) {
        if (!Compat.isVulkanModActive())
            return;

        DhVulkanModEntrypoint.deferredComposite();
    }
}
