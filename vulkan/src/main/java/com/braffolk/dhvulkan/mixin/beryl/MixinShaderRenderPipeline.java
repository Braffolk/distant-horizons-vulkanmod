package com.braffolk.dhvulkan.mixin.beryl;

import com.braffolk.dhvulkan.beryl.BerylCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Mixin into Beryl's RenderingPipeline to initialize DH integration
 * when Beryl's pipeline starts up.
 *
 * Beryl 0.2.0-alpha uses preInit() and initResources() as initialization
 * points (no init() method). We hook preInit with require=0 so the mixin
 * silently skips if the method name differs in future Beryl versions.
 * BerylCompat.initialize() also has a lazy initialization fallback
 * via ensureInitialized().
 */
@Mixin(value = net.beryl.render.RenderingPipeline.class, remap = false)
public class MixinShaderRenderPipeline {

    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod-Beryl");

    /**
     * Hook Beryl's preInit() to register DH integration.
     * preInit() is called during resource loading, before rendering starts.
     */
    @Inject(
            method = "preInit",
            at = @At("RETURN"),
            require = 0,
            expect = 0
    )
    private static void dhvulkan$onPipelinePreInit(CallbackInfo ci) {
        if (!BerylCompat.shouldUseVulkanWithBeryl()) return;
        LOGGER.info("[DH-Vulkan-Beryl] Beryl preInit — registering DH integration.");
        BerylCompat.initialize();
    }

    /**
     * Also hook initResources() as a fallback init point.
     */
    @Inject(
            method = "initResources",
            at = @At("RETURN"),
            require = 0,
            expect = 0
    )
    private static void dhvulkan$onPipelineInitResources(CallbackInfo ci) {
        if (!BerylCompat.shouldUseVulkanWithBeryl()) return;
        BerylCompat.ensureInitialized();
    }
}
