package com.braffolk.dhvulkan.core;

import com.seibel.distanthorizons.core.config.Config;

/**
 * Type-safe helper for reading DH config values across DH 2.4 and DH 3.0.
 * <p>
 * DH config entries may return different number types between versions
 * (e.g. Float in 2.4.x vs Double in 3.0). This helper centralizes all
 * numeric config reads with safe Number-based conversion.
 */
public final class DhConfigHelper {

    private DhConfigHelper() {}

    // ==================== //
    // Safe type converters //
    // ==================== //

    /** Safely read any Number config entry as float. */
    public static float toFloat(Object value) {
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        throw new IllegalArgumentException("[DH-VulkanMod] Config value is not a Number: " + value);
    }

    /** Safely read any Number config entry as int. */
    public static int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        throw new IllegalArgumentException("[DH-VulkanMod] Config value is not a Number: " + value);
    }

    /** Safely read any Number config entry as double. */
    public static double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        throw new IllegalArgumentException("[DH-VulkanMod] Config value is not a Number: " + value);
    }

    // ========================= //
    // Terrain config accessors  //
    // ========================= //

    public static float earthCurveRatio() {
        return toFloat(Config.Client.Advanced.Graphics.Experimental.earthCurveRatio.get());
    }

    public static float overdrawPrevention() {
        return toFloat(Config.Client.Advanced.Graphics.Culling.overdrawPrevention.get());
    }

    public static boolean ditherDhFade() {
        return Config.Client.Advanced.Graphics.Quality.ditherDhFade.get();
    }

    public static boolean noiseEnabled() {
        return Config.Client.Advanced.Graphics.NoiseTexture.enableNoiseTexture.get();
    }

    public static int noiseSteps() {
        return toInt(Config.Client.Advanced.Graphics.NoiseTexture.noiseSteps.get());
    }

    public static float noiseIntensity() {
        Object raw = Config.Client.Advanced.Graphics.NoiseTexture.noiseIntensity.get();
        float value = toFloat(raw);
        // Some DH versions store noise intensity as int 0-100 instead of float 0.0-1.0
        if (raw instanceof Integer || value > 1.0f) {
            value = value / 100.0f;
        }
        return value;
    }

    public static int noiseDropoff() {
        return toInt(Config.Client.Advanced.Graphics.NoiseTexture.noiseDropoff.get());
    }

    public static boolean whiteWorldEnabled() {
        return Config.Client.Advanced.Debugging.enableWhiteWorld.get();
    }

    // =================== //
    // SSAO config         //
    // =================== //

    public static boolean ssaoEnabled() {
        return Config.Client.Advanced.Graphics.Ssao.enableSsao.get();
    }

    // =================== //
    // Fog config          //
    // =================== //

    public static boolean dhFogEnabled() {
        return Config.Client.Advanced.Graphics.Fog.enableDhFog.get();
    }

    public static int lodChunkRenderDistanceRadius() {
        return toInt(Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get());
    }

    public static float farFogStart() {
        return toFloat(Config.Client.Advanced.Graphics.Fog.farFogStart.get());
    }

    public static float farFogEnd() {
        return toFloat(Config.Client.Advanced.Graphics.Fog.farFogEnd.get());
    }

    public static float farFogMin() {
        return toFloat(Config.Client.Advanced.Graphics.Fog.farFogMin.get());
    }

    public static float farFogMax() {
        return toFloat(Config.Client.Advanced.Graphics.Fog.farFogMax.get());
    }

    public static float farFogDensity() {
        return toFloat(Config.Client.Advanced.Graphics.Fog.farFogDensity.get());
    }

    public static float heightFogStart() {
        return toFloat(Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogStart.get());
    }

    public static float heightFogEnd() {
        return toFloat(Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogEnd.get());
    }

    public static float heightFogMin() {
        return toFloat(Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogMin.get());
    }

    public static float heightFogMax() {
        return toFloat(Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogMax.get());
    }

    public static float heightFogDensity() {
        return toFloat(Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogDensity.get());
    }

    public static float heightFogBaseHeight() {
        return toFloat(Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogBaseHeight.get());
    }

    // =================== //
    // Fade mode           //
    // =================== //

    public static com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode vanillaFadeMode() {
        return Config.Client.Advanced.Graphics.Quality.vanillaFadeMode.get();
    }
}
