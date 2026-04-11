package com.braffolk.dhvulkan.compat.beryl;


import net.vulkanmod.vulkan.shader.Uniforms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * Dynamically scales Beryl's rendering effects (Fog, Sunsets) originally bound to 
 * Minecraft's rendering distance, stretching them to match Distant Horizon's geometry radius.
 */
public class BerylAtmosphereOverride {
    private static final Logger LOGGER = LoggerFactory.getLogger("DH-Vulkan/BerylAtm");
    private static boolean reflectionInitialized = false;
    private static boolean hooksInjected = false;
    private static Field berylFogFactorField;
    private static Field berylFogEndField;
    
    // Cache the max distance for smooth uniform application
    private static float currentDhClipDistance = 256.0f;

    public static void applyDhDistanceOverrides(float dhClipDistance) {
        currentDhClipDistance = dhClipDistance;

        try {
            if (!reflectionInitialized) {
                // Prepare reflection to intercept Beryl's true FogFactor
                Class<?> rpClass = Class.forName("net.beryl.render.RenderingPipeline");
                
                berylFogFactorField = rpClass.getDeclaredField("FogFactor");
                berylFogFactorField.setAccessible(true);
                
                try {
                    berylFogEndField = rpClass.getDeclaredField("FogEnd");
                    berylFogEndField.setAccessible(true);
                } catch (NoSuchFieldException e) {
                    LOGGER.warn("Beryl RenderingPipeline has no FogEnd field, will use FogStart fallback");
                    berylFogEndField = null;
                }
                
                reflectionInitialized = true;
            }

            if (!hooksInjected) {
                // Inject our Stretched FogEnd and FogStart Suppliers globally into VulkanMod!
                // This forces Beryl's vanilla terrain AND DH LOD terrain to uniformly target the DH Horizon
                // for volumetric fog boundaries and horizon sunset glows.
                
                // FogStart begins at 80% to stretch sunset glows gracefully
                Uniforms.vec1f_uniformMap.put("FogStart", () -> currentDhClipDistance * 0.8f);
                
                // FogEnd hits the strict clipping limit
                Uniforms.vec1f_uniformMap.put("FogEnd", () -> currentDhClipDistance);
                
                // We must stretch FogFactor using inverse proportion so volumetric fog 
                // density diffuses outwards and doesn't hit 100% opacity over the LOD horizon!
                // This resolves the bug where distant LODs turn into bright flat glowing walls of fog.
                Uniforms.vec1f_uniformMap.put("FogFactor", () -> {
                    if (berylFogFactorField == null) return 0.0f;
                    try {
                        float berylFogFactor = (float) berylFogFactorField.get(null);
                        float dhDistance = currentDhClipDistance;
                        // Beryl usually assumes a physical sunset of ~200-400 blocks.
                        // We scale the volumetric exponential term strictly out.
                        float berylDistance;
                        if (berylFogEndField != null) {
                            berylDistance = (float) berylFogEndField.get(null);
                        } else {
                            // Fallback: estimate from MC render distance (16 chunks = 256 blocks typical)
                            berylDistance = 256.0f;
                        }
                        if(berylDistance <= 0.01f || dhDistance <= 0.01f) return berylFogFactor;
                        return berylFogFactor * (berylDistance / dhDistance);
                    } catch (Exception e) {
                        return 0.0f;
                    }
                });

                hooksInjected = true;
                LOGGER.info("Successfully hooked and stretched Beryl's atmospheric coordinates to {} blocks", dhClipDistance);
            }
        } catch (Exception e) {
            if (!reflectionInitialized) {
                LOGGER.error("Failed to inject Beryl atmospheric overrides", e);
                reflectionInitialized = true; // prevent log spam
            }
        }
    }
}
