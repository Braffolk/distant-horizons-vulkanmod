package com.braffolk.dhvulkan.beryl;

import net.fabricmc.loader.api.FabricLoader;
import net.vulkanmod.vulkan.Renderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Runtime detection and utility class for Beryl shader mod integration.
 * Beryl is a Vulkan-based shader pipeline that sits on top of VulkanMod,
 * providing custom terrain, shadow, entity, bloom, and post-processing shaders.
 *
 * When Beryl is active, DH-VulkanMod must:
 * 1. Register DH depth texture samplers so Beryl's shaders can read DH depth
 * 2. Register DH-specific uniforms (dhProjection, dhRenderDistance, etc.)
 * 3. Add the DISTANT_HORIZONS preprocessor define for shader pack conditional code
 * 4. Hook DH LOD rendering into Beryl's rendering pipeline
 * 5. Composite DH's framebuffer into Beryl's render targets (not directly to swapchain)
 *
 * Beryl 0.2.0-alpha API:
 *   - ShaderMainPass.PASS (static field, the singleton instance)
 *   - ShaderMainPass.currentFramebuffer (the active HDR framebuffer)
 *   - ShaderMainPass.renderPass (the render pass for the final/target framebuffer)
 *   - RenderingPipeline.shaderMainPass (static field, same as PASS)
 *   - ShaderMainPass.begin(VkCommandBuffer, MemoryStack) / end(VkCommandBuffer)
 *   - MainPass.rebindMainTarget() via VulkanMod's MainPass interface
 */
public final class BerylCompat {

    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod-Beryl");

    /** True if Beryl mod is loaded and active */
    private static final boolean BERYL_ACTIVE = detectBeryl();

    /** True if Beryl has been successfully initialized for this session */
    private static volatile boolean berylInitialized = false;

    /** Cached reference to Beryl's ShaderMainPass singleton */
    private static volatile Object berylMainPass;

    /**
     * Detect whether Beryl mod is present in the mod loader.
     */
    private static boolean detectBeryl() {
        boolean present = FabricLoader.getInstance().isModLoaded("beryl");
        if (present) {
            LOGGER.info("[DH-Vulkan-Beryl] Beryl shader mod detected. Enabling Beryl integration.");
        }
        return present;
    }

    /** @return true if Beryl mod is loaded */
    public static boolean isBerylActive() {
        return BERYL_ACTIVE;
    }

    /** @return true if both VulkanMod and Beryl are active (full Beryl rendering path) */
    public static boolean isBerylRenderingPath() {
        return BERYL_ACTIVE && com.braffolk.dhvulkan.compat.Compat.isVulkanModActive();
    }

    /**
     * Returns true when DH-VulkanMod's Vulkan renderer is actively being used
     * AND Beryl is present. This is more specific than {@link #isBerylRenderingPath()}
     * because it also checks whether DH's rendering API config would actually
     * select Vulkan rendering.
     *
     * <p>Use this guard in Beryl mixins to prevent injecting DH-Vulkan-specific
     * code when the user has selected OpenGL or Blaze3D rendering in DH's config.</p>
     *
     * @return true if Vulkan rendering is active with Beryl
     */
    public static boolean shouldUseVulkanWithBeryl() {
        if (!BERYL_ACTIVE) return false;
        if (!com.braffolk.dhvulkan.compat.Compat.isVulkanModActive()) return false;
        // Check if Vulkan rendering is actually active (same logic as MixinDependencySetup)
        return isVulkanRenderingSelected();
    }

    /**
     * Check whether the current config would result in Vulkan rendering being used.
     * Matches the logic in {@code MixinDependencySetup.dhvulkan$overrideRenderApi()}.
     */
    private static boolean isVulkanRenderingSelected() {
        // Check force override first
        try {
            if (com.braffolk.dhvulkan.config.DhVulkanConfig.get().forceVulkanRendering) {
                return true;
            }
        } catch (Exception e) {
            // Config not available yet; default to Vulkan when VulkanMod is present
            return true;
        }

        // Check DH's rendering API config
        try {
            Object apiEnum = com.seibel.distanthorizons.core.config.Config
                    .Client.Advanced.Graphics.Experimental.renderingApi.get();
            String apiName = apiEnum.toString();
            return "AUTO".equals(apiName);
        } catch (Exception e) {
            // Config access failed; default to Vulkan when VulkanMod is present
            return true;
        }
    }

    /**
     * Initialize the Beryl integration layer. Resolves Beryl's ShaderMainPass
     * singleton via reflection (to avoid hard compile-time dependency).
     *
     * Beryl 0.2.0-alpha stores its main pass in:
     *   - ShaderMainPass.PASS (static field)
     *   - RenderingPipeline.shaderMainPass (static field)
     *
     * We try multiple resolution strategies for forward compatibility.
     */
    public static synchronized void initialize() {
        if (!BERYL_ACTIVE || berylInitialized) return;

        try {
            // Strategy 1: Direct static field on ShaderMainPass.PASS
            berylMainPass = resolveViaPassField();
            if (berylMainPass != null) {
                LOGGER.info("[DH-Vulkan-Beryl] Resolved ShaderMainPass via PASS static field.");
            }

            // Strategy 2: Via RenderingPipeline.shaderMainPass
            if (berylMainPass == null) {
                berylMainPass = resolveViaRenderingPipeline();
                if (berylMainPass != null) {
                    LOGGER.info("[DH-Vulkan-Beryl] Resolved ShaderMainPass via RenderingPipeline.");
                }
            }

            // Strategy 3: Via VulkanMod's Renderer.getMainPass()
            if (berylMainPass == null) {
                try {
                    Object mainPass = Renderer.getInstance().getMainPass();
                    if (mainPass != null) {
                        Class<?> clazz = mainPass.getClass();
                        if (clazz.getName().contains("ShaderMainPass") ||
                                clazz.getName().contains("beryl")) {
                            berylMainPass = mainPass;
                            LOGGER.info("[DH-Vulkan-Beryl] Resolved ShaderMainPass via Renderer.getMainPass().");
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("[DH-Vulkan-Beryl] Renderer.getMainPass() failed: {}", e.getMessage());
                }
            }

            if (berylMainPass != null) {
                berylInitialized = true;
                LOGGER.info("[DH-Vulkan-Beryl] Beryl integration initialized successfully.");
                registerDhResources();
            } else {
                LOGGER.warn("[DH-Vulkan-Beryl] Could not resolve ShaderMainPass. Beryl integration will use fallback path.");
            }

        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan-Beryl] Failed to initialize Beryl integration", e);
        }
    }

    /**
     * Resolve ShaderMainPass via ShaderMainPass.PASS static field.
     */
    private static Object resolveViaPassField() {
        try {
            Class<?> clazz = Class.forName("net.beryl.render.ShaderMainPass");
            java.lang.reflect.Field passField = clazz.getDeclaredField("PASS");
            passField.setAccessible(true);
            Object pass = passField.get(null);
            if (pass != null) {
                return pass;
            }
        } catch (Exception e) {
            LOGGER.debug("[DH-Vulkan-Beryl] PASS field resolution failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Resolve ShaderMainPass via RenderingPipeline.shaderMainPass static field.
     */
    private static Object resolveViaRenderingPipeline() {
        try {
            Class<?> clazz = Class.forName("net.beryl.render.RenderingPipeline");
            java.lang.reflect.Field field = clazz.getDeclaredField("shaderMainPass");
            field.setAccessible(true);
            Object mainPass = field.get(null);
            if (mainPass != null) {
                return mainPass;
            }
        } catch (Exception e) {
            LOGGER.debug("[DH-Vulkan-Beryl] RenderingPipeline.shaderMainPass resolution failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Attempt lazy initialization if not yet done.
     * Called at runtime before accessing Beryl resources.
     */
    public static synchronized void ensureInitialized() {
        if (!BERYL_ACTIVE || berylInitialized) return;
        initialize();
    }

    /**
     * Register Distant Horizons depth textures and uniforms with Beryl's
     * rendering pipeline.
     */
    private static void registerDhResources() {
        try {
            DhBerylSamplers.registerSamplers();
            DhBerylUniforms.registerUniforms();
            DhBerylDefines.registerDefines();
            LOGGER.info("[DH-Vulkan-Beryl] DH resources registered with Beryl pipeline.");
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan-Beryl] Failed to register DH resources with Beryl", e);
        }
    }

    /** @return Beryl's ShaderMainPass instance, or null if not available */
    public static Object getBerylMainPass() {
        if (!berylInitialized) {
            ensureInitialized();
        }
        return berylMainPass;
    }

    /**
     * Get Beryl's currently active HDR framebuffer.
     * Uses ShaderMainPass.currentFramebuffer field.
     *
     * @return Beryl's active Framebuffer, or null
     */
    public static net.vulkanmod.vulkan.framebuffer.Framebuffer getBerylFramebuffer() {
        Object mainPass = getBerylMainPass();
        if (mainPass == null) return null;
        try {
            java.lang.reflect.Field fbField = mainPass.getClass().getDeclaredField("currentFramebuffer");
            fbField.setAccessible(true);
            Object fb = fbField.get(mainPass);
            return (net.vulkanmod.vulkan.framebuffer.Framebuffer) fb;
        } catch (Exception e) {
            LOGGER.debug("[DH-Vulkan-Beryl] Could not get Beryl currentFramebuffer: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Captures Beryl's current render pass state (RenderPass + Framebuffer)
     * so DH can re-enter Beryl's render pass after DH's own render passes end.
     *
     * Beryl's ShaderMainPass stores:
     *   - renderPass (the RenderPass for the target framebuffer)
     *   - currentFramebuffer (the Framebuffer being rendered to)
     *
     * @return captured state, or null if unavailable
     */
    public static BerylRenderState captureBerylRenderState() {
        Object mainPass = getBerylMainPass();
        if (mainPass == null) return null;
        try {
            Class<?> mainPassClass = mainPass.getClass();

            java.lang.reflect.Field rpField = mainPassClass.getDeclaredField("renderPass");
            rpField.setAccessible(true);
            Object renderPass = rpField.get(mainPass);

            java.lang.reflect.Field fbField = mainPassClass.getDeclaredField("currentFramebuffer");
            fbField.setAccessible(true);
            Object framebuffer = fbField.get(mainPass);

            if (renderPass instanceof net.vulkanmod.vulkan.framebuffer.RenderPass
                    && framebuffer instanceof net.vulkanmod.vulkan.framebuffer.Framebuffer) {
                return new BerylRenderState(
                        (net.vulkanmod.vulkan.framebuffer.RenderPass) renderPass,
                        (net.vulkanmod.vulkan.framebuffer.Framebuffer) framebuffer);
            }
        } catch (Exception e) {
            LOGGER.debug("[DH-Vulkan-Beryl] Could not capture Beryl render state: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Signal to Beryl that DH has completed its LOD rendering phase.
     */
    public static void onDhRenderingComplete() {
        // No-op in current Beryl version
    }

    /**
     * Holds Beryl's current render pass state, captured before DH tears down
     * its own framebuffer pass. DH uses this to re-enter Beryl's HDR pass
     * for compositing.
     */
    public static class BerylRenderState {
        public final net.vulkanmod.vulkan.framebuffer.RenderPass renderPass;
        public final net.vulkanmod.vulkan.framebuffer.Framebuffer framebuffer;

        public BerylRenderState(net.vulkanmod.vulkan.framebuffer.RenderPass renderPass,
                               net.vulkanmod.vulkan.framebuffer.Framebuffer framebuffer) {
            this.renderPass = renderPass;
            this.framebuffer = framebuffer;
        }
    }
}
