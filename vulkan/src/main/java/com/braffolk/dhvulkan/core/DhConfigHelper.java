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
        return Config.Client.Advanced.Graphics.enableSsao.get();
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

    // ======================================= //
    // Overdraw / clip distance (full pipeline) //
    // ======================================= //

    // Speed thresholds for dynamic overdraw (blocks/sec)
    private static final float DYNAMIC_MIN_SPEED = 10.0f;  // slightly above sprinting (~7)
    private static final float DYNAMIC_MAX_SPEED = 100.0f;  // max spectator speed
    private static final float DYNAMIC_MIN_OVERDRAW_RATIO = 0.2f;

    /**
     * Returns the base overdraw ratio from config, applying auto-mode
     * distance-based selection if config is negative (auto).
     *
     * @param renderDistChunks the vanilla render distance in chunks
     */
    public static float getBaseOverdrawRatio(int renderDistChunks) {
        float overdraw = overdrawPrevention();
        if (overdraw < 0) {
            // Auto mode: pick ratio based on vanilla render distance
            if (renderDistChunks <= 2) overdraw = 0.2f;
            else if (renderDistChunks <= 4) overdraw = 0.3f;
            else if (renderDistChunks <= 6) overdraw = 0.6f;
            else if (renderDistChunks <= 10) overdraw = 0.8f;
            else overdraw = 0.9f;
        } else {
            overdraw = Math.max(0.05f, Math.min(overdraw, 1.0f));
        }
        return overdraw;
    }

    /**
     * Apply velocity-based reduction to the overdraw ratio.
     * At high speeds (elytra, spectator), the overdraw pulls closer to the camera
     * so MC terrain doesn't outrun DH terrain, preventing gaps.
     *
     * Uses MC's player velocity directly — works on all DH versions, no reflection needed.
     * Matches DH base's behavior: speed thresholds of 10-100 blocks/sec.
     */
    private static double speedAverage = 0.0;
    private static final double SPEED_SMOOTH_FACTOR = 0.1; // EMA alpha — lower = smoother
    private static boolean velocityDiagLogged = false;

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("DH-VulkanMod");

    public static float applyVelocityReduction(float overdraw) {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || mc.player == null) return overdraw;

            // getDeltaMovement() returns blocks/tick — convert to blocks/sec (×20 tps)
            var delta = mc.player.getDeltaMovement();
            double currentSpeed = Math.sqrt(delta.x * delta.x + delta.y * delta.y + delta.z * delta.z) * 20.0;

            // Exponential moving average for smooth transitions
            speedAverage = speedAverage * (1.0 - SPEED_SMOOTH_FACTOR) + currentSpeed * SPEED_SMOOTH_FACTOR;

            if (speedAverage >= DYNAMIC_MIN_SPEED) {
                float speedRange = (float) ((DYNAMIC_MAX_SPEED - speedAverage) / DYNAMIC_MAX_SPEED);
                speedRange = Math.max(speedRange, DYNAMIC_MIN_OVERDRAW_RATIO);
                overdraw *= speedRange;

                if (!velocityDiagLogged) {
                    velocityDiagLogged = true;
                    LOGGER.info("[DH-VulkanMod] Velocity overdraw active: speed={}, overdraw={}",
                            String.format("%.1f", speedAverage), String.format("%.3f", overdraw));
                }
            }
        } catch (Exception e) {
            // Safely ignore — player not available yet
        }
        return overdraw;
    }

    /**
     * Height-based near clip override.
     * When the player is 1000+ blocks above the world max height,
     * use a larger near clip to prevent Z-precision issues.
     *
     * @return override distance in blocks, or -1 if no override needed
     */
    public static float getHeightBasedNearClipOverride() {
        try {
            var mc = com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector.INSTANCE
                    .get(com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper.class);
            var level = mc.getWrappedClientLevel();
            if (level != null) {
                int playerHeight = mc.getPlayerBlockPos().getY();
                int levelMaxHeight = level.getMaxHeight();
                if (playerHeight > levelMaxHeight + 1_000) {
                    return playerHeight - (levelMaxHeight + 1000);
                }
            }
        } catch (Exception e) {
            // Safely ignore
        }
        return -1.0f;
    }

    /**
     * Compute the near clip plane distance in blocks, matching DH's
     * {@code RenderUtil.getNearClipPlaneInBlocks()} exactly.
     * Includes: auto-mode, velocity reduction, height override, and FOV/aspect normalization.
     *
     * @param renderDistChunks the vanilla render distance in chunks
     * @param viewportWidth    framebuffer viewport width in pixels
     * @param viewportHeight   framebuffer viewport height in pixels
     */
    public static float getNearClipPlaneInBlocks(int renderDistChunks, int viewportWidth, int viewportHeight, boolean lodOnly) {
        float overdraw = getBaseOverdrawRatio(renderDistChunks);
        overdraw = applyVelocityReduction(overdraw);

        float nearClipPlane;
        if (lodOnly) {
            nearClipPlane = 0.1f;
        } else {
            nearClipPlane = renderDistChunks * 16.0f * overdraw;
            if (nearClipPlane < 0.1f) {
                nearClipPlane = 0.1f;
            }
        }

        // Height-based override for Z-precision at extreme altitudes
        float heightOverride = getHeightBasedNearClipOverride();
        if (heightOverride != -1.0f) {
            nearClipPlane = heightOverride;
        }

        // FOV/aspect-ratio normalization (fixed 70° to match DH's behavior)
        double fov = 70.0;
        double aspectRatio = (double) viewportWidth / viewportHeight;
        double tanHalfFov = Math.tan(fov / 180.0 * Math.PI / 2.0);
        nearClipPlane = (float) (nearClipPlane / Math.sqrt(1.0 + tanHalfFov * tanHalfFov * (aspectRatio * aspectRatio + 1.0)));

        // NOTE: DH base applies Math.min(nearClipPlane, 7.5f) only for the PROJECTION
        // MATRIX near clip plane (in createLodProjectionMatrix), NOT for uClipDistance
        // or fade distances. We do NOT cap here — DH handles the projection cap internally.

        return nearClipPlane;
    }

    /**
     * Compute the final uClipDistance uniform value for the terrain shader.
     * This is the near clip plane + 16 block buffer (unless in lodOnly mode).
     *
     * @param renderDistChunks the vanilla render distance in chunks
     * @param viewportWidth    framebuffer viewport width in pixels
     * @param viewportHeight   framebuffer viewport height in pixels
     */
    public static float getShaderClipDistance(int renderDistChunks, int viewportWidth, int viewportHeight) {
        boolean lodOnly = Config.Client.Advanced.Debugging.lodOnlyMode.get();
        float clipDist = getNearClipPlaneInBlocks(renderDistChunks, viewportWidth, viewportHeight, lodOnly);
        if (!lodOnly) {
            // +16 block buffer prevents the near clip and fragment discard circle from touching
            clipDist += 16.0f;
        }
        return clipDist;
    }
}

