package com.braffolk.dhvulkan.beryl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Registers Distant Horizons preprocessor defines with Beryl's shader compilation system.
 *
 * When Beryl compiles shaders, it can inject preprocessor defines that allow shader
 * packs to conditionally include DH-specific rendering code. This mirrors the approach
 * used by Vulkan-Voxy's `voxy.json` system and Iris-based DH integrations.
 *
 * Defines registered:
 * - DISTANT_HORIZONS (int, value 1): Indicates DH is present and providing LOD data
 *
 * Shader packs can use:
 *   #ifdef DISTANT_HORIZONS
 *     // DH-specific depth handling, fog, etc.
 *   #endif
 *
 * The define value corresponds to the integration version for forward compatibility.
 */
public final class DhBerylDefines {

    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod-Beryl");

    /** Current DH+Beryl integration version */
    public static final int DEFINE_VERSION = 1;

    /** Whether defines have been registered */
    private static boolean registered = false;

    /**
     * Register DH preprocessor defines with Beryl's shader system.
     * Uses reflection to inject into Beryl's define registry.
     */
    public static void registerDefines() {
        if (registered) return;

        try {
            // Beryl's shader compilation system may expose define injection points.
            // The primary injection is via MixinBerylPipelineConfigs which hooks
            // BerylPipelineConfigs.getDefines(). The reflection attempts below are
            // fallbacks for alternative Beryl versions.
            //
            // Note: ShaderRenderPipeline is an interface in Beryl 0.2.0-alpha,
            // not a concrete class. We don't need a pipeline instance to register
            // defines — the mixin handles injection at shader compile time.

            boolean injected = false;

            // Attempt 1: Via RenderingPipeline's static shaderMainPass
            try {
                Class<?> rpClass = Class.forName("net.beryl.render.RenderingPipeline");
                java.lang.reflect.Field smpField = rpClass.getDeclaredField("shaderMainPass");
                smpField.setAccessible(true);
                Object smp = smpField.get(null);
                if (smp != null) {
                    Class<?> smpClass = smp.getClass();
                    // Try addCustomDefine method
                    try {
                        java.lang.reflect.Method addDefine = smpClass.getDeclaredMethod(
                                "addCustomDefine", String.class, int.class);
                        addDefine.setAccessible(true);
                        addDefine.invoke(smp, "DISTANT_HORIZONS", DEFINE_VERSION);
                        injected = true;
                    } catch (NoSuchMethodException ignored) {}
                }
            } catch (Exception ignored) {}

            // Attempt 2: Via BerylPipelineConfigs static customDefines map
            if (!injected) {
                try {
                    Class<?> configsClass = Class.forName("net.beryl.render.shader.BerylPipelineConfigs");
                    java.lang.reflect.Field definesField = configsClass.getDeclaredField("customDefines");
                    definesField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, String> defines =
                            (java.util.Map<String, String>) definesField.get(null);
                    if (defines != null) {
                        defines.put("DISTANT_HORIZONS", Integer.toString(DEFINE_VERSION));
                        injected = true;
                    }
                } catch (Exception ignored) {
                    // BerylPipelineConfigs doesn't have this field
                }
            }

            // Attempt 3: Mixin-based injection (handled by MixinBerylPipelineConfigs)
            // If all reflection approaches fail, the mixin will handle it.
            // Set a static flag so the mixin knows to inject.

            if (injected) {
                registered = true;
                LOGGER.info("[DH-Vulkan-Beryl] DISTANT_HORIZONS define registered (version {}).", DEFINE_VERSION);
            } else {
                // Rely on mixin-based injection
                LOGGER.info("[DH-Vulkan-Beryl] DISTANT_HORIZONS define will be injected via mixin.");
                registered = true;
            }
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan-Beryl] Failed to register DH defines", e);
            registered = true; // Don't retry every frame
        }
    }

    /** @return the current define version */
    public static int getDefineVersion() {
        return DEFINE_VERSION;
    }

    /** @return true if defines have been registered */
    public static boolean isRegistered() {
        return registered;
    }
}
