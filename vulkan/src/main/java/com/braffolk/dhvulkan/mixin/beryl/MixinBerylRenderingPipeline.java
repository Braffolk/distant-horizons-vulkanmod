package com.braffolk.dhvulkan.mixin.beryl;

import com.braffolk.dhvulkan.compat.BerylAccessor;
import com.braffolk.dhvulkan.compat.Compat;
import com.braffolk.dhvulkan.core.VulkanRenderEngine;
import net.minecraft.client.Camera;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects DH LOD shadow rendering into Beryl's shadow pass.
 */
@Mixin(targets = "net.beryl.render.ShadowMap", remap = false)
public abstract class MixinBerylRenderingPipeline {

    @Inject(
            method = "renderShadowMap",
            at = @At("HEAD"),
            require = 0
    )
    private void dhvulkan$renderLodShadows(Camera camera, PoseStack poseStack, Matrix4f projection, net.minecraft.client.DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!com.braffolk.dhvulkan.config.DhVulkanConfig.get().berylShadowsEnabled) {
            return;
        }

        Object renderParams = Compat.getLastRenderParams();
        if (renderParams == null) return;

        // 1. Tell DH we are doing a shadow pass
        BerylAccessor.setRenderingShadowPass(true);

        // 2. Set the global Vulkan shadow matrices
        VulkanRenderEngine.getInstance().enableShadowState(poseStack.last().pose(), projection);

        try {
            // 3. Render LODs using DH's own internal logic via Compat wrapper
            Compat.renderShadowLods();
        } catch (Exception e) {
            org.apache.logging.log4j.LogManager.getLogger("DH-VulkanShadow").error("Shadow render failed", e);
        } finally {
            // 4. Reset
            BerylAccessor.setRenderingShadowPass(false);
            VulkanRenderEngine.getInstance().disableShadowState();
        }
    }
}
