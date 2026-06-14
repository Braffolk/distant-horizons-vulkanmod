package com.braffolk.dhvulkan.mixin.beryl;

import com.braffolk.dhvulkan.beryl.BerylCompat;
import com.braffolk.dhvulkan.bridge.DhIntegration;
import com.braffolk.dhvulkan.DhVulkanModEntrypoint;
import com.braffolk.dhvulkan.core.DhConfigHelper;
import com.braffolk.dhvulkan.core.VulkanBackend;
import com.braffolk.dhvulkan.core.VulkanRenderEngine;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Mixin into Minecraft's FogRenderer to extend fog parameters when DH + Beryl
 * are both active.
 *
 * MC 26.1.2 Fog System:
 * FogRenderer.setupFog() is an instance method that returns FogData containing:
 *   - environmentalStart/End: atmospheric fog range
 *   - renderDistanceStart/End: render distance fog range
 *   - skyEnd, cloudEnd: sky/cloud fog ranges
 *   - color (Vector4f): fog color
 *
 * VulkanMod copies FogData into VRenderSystem.fogData, which Beryl's shaders
 * read as uniforms (FogEnd, FogStart, FogFactor, FogColor).
 *
 * Beryl's fog2.glsl applies render-distance fog:
 *   if (vertexDistance > 0.8 * FogEnd) { color = fog(color, vertexDistance, FogEnd, fogColor); }
 * MC sets FogEnd = renderDistance * 16 (e.g., 32 chunks * 16 = 512 blocks).
 * At the DH/MC boundary (~512 blocks), MC terrain is fully fogged.
 *
 * Fix: When Beryl + DH are active, extend renderDistanceEnd to cover DH's LOD
 * draw distance. Also scale renderDistanceStart proportionally to maintain
 * fog density. This prevents Beryl from fogging out the DH/MC boundary.
 */
@Mixin(value = FogRenderer.class, remap = true)
public class MixinFogRenderer {

    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod-Beryl");

    /**
     * After MC's FogRenderer.setupFog() returns FogData, extend the fog range
     * when DH + Beryl are active. Uses @Inject on RETURN to modify the returned
     * FogData object before VulkanMod copies it into VRenderSystem.fogData.
     *
     * MC 26.1.2: setupFog(Camera, int, DeltaTracker, float, ClientLevel) -> FogData
     * Uses require=0/expect=0 for version compatibility.
     */
    @Inject(
            method = "setupFog",
            at = @At("RETURN"),
            require = 0,
            expect = 0
    )
    private void dhvulkan$extendFogForBeryl(CallbackInfoReturnable<FogData> cir) {
        if (!BerylCompat.shouldUseVulkanWithBeryl()) return;

        FogData fogData = cir.getReturnValue();
        if (fogData == null) return;

        try {
            DhIntegration integration = DhVulkanModEntrypoint.getActiveIntegration();
            if (integration == null) return;

            VulkanBackend backend = integration.getBackend();
            if (!(backend instanceof VulkanRenderEngine engine) || !engine.isInitialized()) return;

            // Get DH's LOD draw distance in blocks
            int dhLodDist = getDhLodDrawDistance();
            if (dhLodDist <= 0) return;

            // Extend renderDistanceEnd to cover DH's LOD range
            float mcRdEnd = fogData.renderDistanceEnd;
            float mcRdStart = fogData.renderDistanceStart;
            float newRdEnd = Math.max(mcRdEnd, (float) dhLodDist);

            if (newRdEnd > mcRdEnd + 1.0f) {
                // Scale start proportionally to maintain fog density curve
                float ratio = newRdEnd / mcRdEnd;
                float newRdStart = mcRdStart * ratio;

                fogData.renderDistanceStart = newRdStart;
                fogData.renderDistanceEnd = newRdEnd;

                // Also extend environmental fog to match (optional but helps
                // with atmospheric fog consistency across DH/MC boundary)
                float mcEnvEnd = fogData.environmentalEnd;
                float newEnvEnd = Math.max(mcEnvEnd, (float) dhLodDist);
                if (newEnvEnd > mcEnvEnd + 1.0f && mcEnvEnd > 0) {
                    float envRatio = newEnvEnd / mcEnvEnd;
                    fogData.environmentalStart = fogData.environmentalStart * envRatio;
                    fogData.environmentalEnd = newEnvEnd;
                }

                // Also extend sky/cloud fog to match
                fogData.skyEnd = Math.max(fogData.skyEnd, (float) dhLodDist);
                fogData.cloudEnd = Math.max(fogData.cloudEnd, (float) dhLodDist);

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("[DH-Vulkan-Beryl] Extended fog for DH+Beryl: " +
                            "rdStart {:.0f}->{:.0f}, rdEnd {:.0f}->{:.0f} (DH LOD: {})",
                            String.format("%.0f", mcRdStart), String.format("%.0f", newRdStart),
                            String.format("%.0f", mcRdEnd), String.format("%.0f", newRdEnd),
                            dhLodDist);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[DH-Vulkan-Beryl] Failed to extend fog: {}", e.getMessage());
        }
    }

    /**
     * Get DH's effective LOD draw distance in blocks.
     * Uses DhConfigHelper which safely reads DH config across versions.
     */
    private static int getDhLodDrawDistance() {
        try {
            // DH config: Quality.lodChunkRenderDistanceRadius returns chunk radius
            int chunkRadius = DhConfigHelper.lodChunkRenderDistanceRadius();
            if (chunkRadius > 0) {
                return chunkRadius * 16;
            }
        } catch (Exception e) {
            // Config access may differ — fall through
        }

        // Fallback: MC render distance * 3 (typical DH LOD range)
        try {
            int mcChunks = net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance();
            return mcChunks * 16 * 3;
        } catch (Exception e) {
            return 0;
        }
    }
}
