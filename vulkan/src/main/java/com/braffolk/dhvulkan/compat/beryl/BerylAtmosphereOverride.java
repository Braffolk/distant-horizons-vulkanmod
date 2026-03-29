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
                
                // The volumetric density factor must NOT be stretched.
                // The physical density of Beryl's fog applies exactly as intended over distance. 
                // We leave FogFactor tied natively to Beryl's base evaluation.

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
