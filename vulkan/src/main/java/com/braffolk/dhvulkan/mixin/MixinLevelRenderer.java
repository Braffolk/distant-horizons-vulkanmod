package com.braffolk.dhvulkan.mixin;

import com.braffolk.dhvulkan.duck.IVulkanGLProxy;
import com.braffolk.dhvulkan.duck.IVulkanLodRenderer;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
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
 * composited
 * onto MC's render target with correct depth testing (MC terrain overwrites
 * LODs).
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void dhvulkan$compositeAfterMcRender(CallbackInfo ci) {
        if (!IVulkanGLProxy.isVulkanModActive())
            return;

        IVulkanLodRenderer lodRenderer = (IVulkanLodRenderer) LodRenderer.INSTANCE;
        lodRenderer.dhvulkan$compositeVulkanFrame();
    }
}
