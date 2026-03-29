package com.braffolk.dhvulkan.compat;

import net.vulkanmod.vulkan.memory.buffer.VertexBuffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.Buffer;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.texture.VTextureSelector;

import java.nio.ByteBuffer;

/**
 * Single source of truth for ALL version-specific API differences.
 * NO other file should contain #if blocks.
 *
 * As of this version, the minimum supported MC version is 1.21.1 (VM 0.6.x).
 * The Compat layer is retained for forward compatibility with MC 26.1+.
 */
public final class Compat {

    // ========================= //
    // Cached reflection fields  //
    // ========================= //

    private static java.lang.reflect.Field vulkanImageIdField;
    private static java.lang.reflect.Field vulkanImageViewField;
    private static java.lang.reflect.Field vulkanImageLayoutField;
    private static java.lang.reflect.Field rendererCmdBufferField;

    // Reusable float[3] for getCloudColorRGB — avoids per-frame allocation
    private static final float[] cloudColorResult = new float[3];

    static {
        try {
            vulkanImageIdField = VulkanImage.class.getDeclaredField("id");
            vulkanImageIdField.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            vulkanImageViewField = VulkanImage.class.getDeclaredField("mainImageView");
            vulkanImageViewField.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            vulkanImageLayoutField = VulkanImage.class.getDeclaredField("currentLayout");
            vulkanImageLayoutField.setAccessible(true);
        } catch (Exception ignored) {}
        try {
            rendererCmdBufferField = Renderer.class.getDeclaredField("currentCmdBuffer");
            rendererCmdBufferField.setAccessible(true);
        } catch (Exception ignored) {}
    }

    // ========================= //
    // VulkanMod detection       //
    // ========================= //

    private static final boolean VULKANMOD_ACTIVE = detectVulkanMod();

    private static boolean detectVulkanMod() {
        try {
            Class.forName("net.vulkanmod.vulkan.Renderer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** @return true if VulkanMod is loaded (no GL context available) */
    public static boolean isVulkanModActive() {
        return VULKANMOD_ACTIVE;
    }

    // ========================= //
    // Beryl detection           //
    // ========================= //

    private static final boolean BERYL_ACTIVE = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("beryl");

    /** @return true if the Beryl shader mod is loaded alongside VulkanMod */
    public static boolean isBerylActive() {
        return BERYL_ACTIVE;
    }

    private static boolean berylRegistered = false;

    /**
     * Registers Beryl as an IrisAccessor with DH, so DH handles shadow map logic.
     * Call this during VulkanBackend initialization.
     */
    public static void registerBerylAccessor() {
        if (!BERYL_ACTIVE || berylRegistered) return;
        try {
            com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector.INSTANCE.bind(
                    com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor.class,
                    new BerylAccessor());
            berylRegistered = true;
            LOGGER.info("[DH-VulkanMod] Registered BerylAccessor with DH");
        } catch (Exception e) {
            LOGGER.error("[DH-VulkanMod] Failed to register BerylAccessor", e);
        }
    }

    // ========================= //
    // Deferred composite hook   //
    // ========================= //

    /**
     * Static hook for Phase 2 deferred composite.
     * Set by MixinLodRenderer (dh24 module), called by MixinLevelRenderer (shared module).
     * This bridges the module boundary without requiring shared code to import dh24 types.
     */
    private static Runnable deferredCompositeHook;
    /** Per-frame flag: true if Phase 2a (addCloudsPass) already fired this frame. */
    private static boolean deferredCompositeRanThisFrame = false;

    public static void setDeferredCompositeHook(Runnable hook) {
        deferredCompositeHook = hook;
    }

    public static void runDeferredCompositeHook() {
        deferredCompositeRanThisFrame = true;
        Runnable hook = deferredCompositeHook;
        if (hook != null) hook.run();
    }

    /**
     * Static hook for Phase 2b late re-composite.
     * Set by MixinLodRenderer (dh24 module), called by MixinLevelRenderer (shared module)
     * at renderLevel @RETURN.
     */
    private static Runnable lateCompositeHook;

    public static void setLateCompositeHook(Runnable hook) {
        lateCompositeHook = hook;
    }

    private static Object lastRenderParamsDH3 = null;

    /**
     * Stores the latest DH 3.0 RenderParams. Called by VkMetaRenderer.
     */
    public static void setLastRenderParamsDH3(Object params) {
        lastRenderParamsDH3 = params;
    }

    // ================================= //
    // Beryl Rendering Uniform Sync      //
    // ================================= //
    
    private static java.lang.reflect.Field berylLightDirField;
    private static java.lang.reflect.Field berylLightColorField;
    private static java.lang.reflect.Field berylSkyColorField;
    private static java.lang.reflect.Field berylUpVectorField;
    private static java.lang.reflect.Field berylFogFactorField;
    private static java.lang.reflect.Field berylNightMultiplierField;
    private static java.lang.reflect.Field berylLightVisibilityField;
    private static java.lang.reflect.Method berylGetLightIntensityMethod;
    private static boolean berylReflectionInitialized = false;

    public static void updateBerylCompatUniforms(java.util.Map<String, net.vulkanmod.vulkan.util.MappedBuffer> dhUniforms, float partialTicks) {
        if (!isBerylActive()) return;
        
        try {
            if (!berylReflectionInitialized) {
                Class<?> rpClass = Class.forName("net.beryl.render.RenderingPipeline");
                berylLightDirField = rpClass.getDeclaredField("LightDir");
                berylLightDirField.setAccessible(true);
                berylLightColorField = rpClass.getDeclaredField("LightColor");
                berylLightColorField.setAccessible(true);
                berylSkyColorField = rpClass.getDeclaredField("SkyColor");
                berylSkyColorField.setAccessible(true);
                berylUpVectorField = rpClass.getDeclaredField("UpVector");
                berylUpVectorField.setAccessible(true);
                berylFogFactorField = rpClass.getDeclaredField("FogFactor");
                berylFogFactorField.setAccessible(true);
                berylNightMultiplierField = rpClass.getDeclaredField("NightMultiplier");
                berylNightMultiplierField.setAccessible(true);
                berylLightVisibilityField = rpClass.getDeclaredField("LightVisibility");
                berylLightVisibilityField.setAccessible(true);
                berylGetLightIntensityMethod = rpClass.getDeclaredMethod("getLightIntensity");
                berylGetLightIntensityMethod.setAccessible(true);
                
                java.lang.reflect.Field berylMinAmbientField = rpClass.getDeclaredField("MinAmbientLight");
                berylMinAmbientField.setAccessible(true);
                java.lang.reflect.Field berylAmbientFactorField = rpClass.getDeclaredField("AmbientLightFactor");
                berylAmbientFactorField.setAccessible(true);
                
                // We cache these inside the method to avoid adding more static fields, since reflection init is cheap
                berylReflectionInitialized = true;
            }

            net.vulkanmod.vulkan.util.MappedBuffer berylLightDir = (net.vulkanmod.vulkan.util.MappedBuffer) berylLightDirField.get(null);
            net.vulkanmod.vulkan.util.MappedBuffer berylLightColor = (net.vulkanmod.vulkan.util.MappedBuffer) berylLightColorField.get(null);
            net.vulkanmod.vulkan.util.MappedBuffer berylSkyColor = (net.vulkanmod.vulkan.util.MappedBuffer) berylSkyColorField.get(null);
            net.vulkanmod.vulkan.util.MappedBuffer berylUpVector = (net.vulkanmod.vulkan.util.MappedBuffer) berylUpVectorField.get(null);
            float fogFactor = (float) berylFogFactorField.get(null);
            float nightMultiplier = (float) berylNightMultiplierField.get(null);
            float lightVisibility = (float) berylLightVisibilityField.get(null);
            float lightIntensity = (float) berylGetLightIntensityMethod.invoke(null);
            
            Class<?> rpClass = Class.forName("net.beryl.render.RenderingPipeline");
            java.lang.reflect.Field minAmbientF = rpClass.getDeclaredField("MinAmbientLight");
            minAmbientF.setAccessible(true);
            float minAmbientLight = (float) minAmbientF.get(null);
            
            java.lang.reflect.Field ambientFactorF = rpClass.getDeclaredField("AmbientLightFactor");
            ambientFactorF.setAccessible(true);
            float ambientLightFactor = (float) ambientFactorF.get(null);

            // Fog color is set by Beryl on the MC RenderSystem, so we just query DH's wrapper
            com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper mcRender = 
                com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector.INSTANCE.get(com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper.class);
            java.awt.Color fogColor = mcRender.getFogColor(partialTicks);

            if (dhUniforms.containsKey("uBerylFogFactor")) dhUniforms.get("uBerylFogFactor").putFloat(0, fogFactor);
            if (dhUniforms.containsKey("uBerylLightIntensity")) dhUniforms.get("uBerylLightIntensity").putFloat(0, lightIntensity);
            if (dhUniforms.containsKey("uBerylNightMultiplier")) dhUniforms.get("uBerylNightMultiplier").putFloat(0, nightMultiplier);
            if (dhUniforms.containsKey("uBerylLightVisibility")) dhUniforms.get("uBerylLightVisibility").putFloat(0, lightVisibility);
            if (dhUniforms.containsKey("uBerylMinAmbientLight")) dhUniforms.get("uBerylMinAmbientLight").putFloat(0, minAmbientLight);
            if (dhUniforms.containsKey("uBerylAmbientLightFactor")) dhUniforms.get("uBerylAmbientLightFactor").putFloat(0, ambientLightFactor);

            if (berylLightDir != null && dhUniforms.containsKey("uBerylLightDir")) {
                dhUniforms.get("uBerylLightDir").putFloat(0, berylLightDir.getFloat(0));
                dhUniforms.get("uBerylLightDir").putFloat(4, berylLightDir.getFloat(4));
                dhUniforms.get("uBerylLightDir").putFloat(8, berylLightDir.getFloat(8));
            }
            if (berylLightColor != null && dhUniforms.containsKey("uBerylLightColor")) {
                dhUniforms.get("uBerylLightColor").putFloat(0, berylLightColor.getFloat(0));
                dhUniforms.get("uBerylLightColor").putFloat(4, berylLightColor.getFloat(4));
                dhUniforms.get("uBerylLightColor").putFloat(8, berylLightColor.getFloat(8));
            }
            if (berylSkyColor != null && dhUniforms.containsKey("uBerylSkyColor")) {
                dhUniforms.get("uBerylSkyColor").putFloat(0, berylSkyColor.getFloat(0));
                dhUniforms.get("uBerylSkyColor").putFloat(4, berylSkyColor.getFloat(4));
                dhUniforms.get("uBerylSkyColor").putFloat(8, berylSkyColor.getFloat(8));
            }
            if (berylUpVector != null && dhUniforms.containsKey("uBerylUpVector")) {
                dhUniforms.get("uBerylUpVector").putFloat(0, berylUpVector.getFloat(0));
                dhUniforms.get("uBerylUpVector").putFloat(4, berylUpVector.getFloat(4));
                dhUniforms.get("uBerylUpVector").putFloat(8, berylUpVector.getFloat(8));
            }
            
            if (dhUniforms.containsKey("uBerylFogColor")) {
                // Beryl overrides VulkanMod's global shaderFogColor directly. 
                // We fetch it straight from the source instead of relying on Vanilla's fallback.
                net.vulkanmod.vulkan.util.MappedBuffer vmFogColor = net.vulkanmod.vulkan.VRenderSystem.getShaderFogColor();
                if (vmFogColor != null) {
                    dhUniforms.get("uBerylFogColor").putFloat(0, vmFogColor.getFloat(0));
                    dhUniforms.get("uBerylFogColor").putFloat(4, vmFogColor.getFloat(4));
                    dhUniforms.get("uBerylFogColor").putFloat(8, vmFogColor.getFloat(8));
                } else {
                    // Fallback just in case
                    float fr = fogColor.getRed() / 255.0f;
                    float fg = fogColor.getGreen() / 255.0f;
                    float fb = fogColor.getBlue() / 255.0f;
                    dhUniforms.get("uBerylFogColor").putFloat(0, (float) Math.pow(fr, 2.2));
                    dhUniforms.get("uBerylFogColor").putFloat(4, (float) Math.pow(fg, 2.2));
                    dhUniforms.get("uBerylFogColor").putFloat(8, (float) Math.pow(fb, 2.2));
                }
            }

        } catch (Exception e) {
            // Only log once if reflection fails
            if (berylReflectionInitialized) {
                LOGGER.error("Failed to sync Beryl compat uniforms", e);
                berylReflectionInitialized = false; 
            }
        }
    }

    /**
     * Retrieves the latest DH RenderParams (or DhApiRenderParam in 2.4).
     */
    public static Object getLastRenderParams() {
        if (com.braffolk.dhvulkan.bridge.DhVersionDetector.detect() != com.braffolk.dhvulkan.bridge.DhVersionDetector.DhVersion.DH_3_0) {
            return ((com.braffolk.dhvulkan.dh24.duck.IVulkanLodRenderer) com.seibel.distanthorizons.core.render.renderer.LodRenderer.INSTANCE).dhvulkan$getLastVulkanRenderParams();
        }
        return lastRenderParamsDH3;
    }

    @SuppressWarnings("unchecked")
    public static void renderShadowLods() {
        Object renderParams = getLastRenderParams();
        if (renderParams == null) return;
        
        com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper profiler = com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector.INSTANCE.get(com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper.class);

        if (com.braffolk.dhvulkan.bridge.DhVersionDetector.detect() == com.braffolk.dhvulkan.bridge.DhVersionDetector.DhVersion.DH_3_0) {
            // DH 3.0 LodRenderer.render
            try {
                com.seibel.distanthorizons.core.render.renderer.LodRenderer.class.getMethod(
                    "render", 
                    com.seibel.distanthorizons.core.render.RenderParams.class, 
                    com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper.class
                ).invoke(com.seibel.distanthorizons.core.render.renderer.LodRenderer.INSTANCE, renderParams, profiler);
            } catch (Exception e) {
                LOGGER.error("Failed to render DH 3.0 shadows", e);
            }
        } else {
            // DH 2.4 LodRenderer.renderLodPass
            try {
                com.seibel.distanthorizons.core.render.renderer.LodRenderer.class.getMethod(
                    "renderLodPass", 
                    com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam.class, 
                    com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper.class
                ).invoke(com.seibel.distanthorizons.core.render.renderer.LodRenderer.INSTANCE, renderParams, profiler);
            } catch (Exception e) {
                LOGGER.error("Failed to render DH 2.4 shadows", e);
            }
        }
    }

    public static void runLateCompositeHook() {
        // Safety net: if Phase 2a didn't fire (e.g. mixin target missing), run it now
        if (!deferredCompositeRanThisFrame) {
            Runnable deferred = deferredCompositeHook;
            if (deferred != null) deferred.run();
        }
        deferredCompositeRanThisFrame = false; // reset for next frame

        Runnable hook = lateCompositeHook;
        if (hook != null) hook.run();
    }

    // ========================= //
    // Buffer factories          //
    // ========================= //

    public static Object createVertexBuffer(int sizeBytes) {
        return new VertexBuffer(sizeBytes, MemoryTypes.HOST_MEM);
    }

    public static Object createGpuVertexBuffer(int sizeBytes) {
        return new VertexBuffer(sizeBytes, MemoryTypes.GPU_MEM);
    }

    public static Object createIndexBuffer(int sizeBytes) {
        return new IndexBuffer(sizeBytes, MemoryTypes.HOST_MEM, IndexBuffer.IndexType.UINT32);
    }

    // ========================= //
    // Buffer operations         //
    // ========================= //

    /**
     * Wait for the GPU to finish all in-flight work.
     * Must be called before freeing resources that may still be in use by
     * a previous frame. This is expensive — only use during cleanup/reinit,
     * never per-frame.
     */
    public static void waitDeviceIdle() {
        org.lwjgl.vulkan.VK10.vkDeviceWaitIdle(net.vulkanmod.vulkan.Vulkan.getVkDevice());
    }

    public static long getBufferId(Object buffer) {
        return ((Buffer) buffer).getId();
    }

    public static void scheduleFree(Object buffer) {
        ((Buffer) buffer).scheduleFree();
    }

    public static void copyBuffer(Object buffer, ByteBuffer data, int size) {
        ((Buffer) buffer).copyBuffer(data, size);
    }

    // ========================= //
    // Draw calls                //
    // ========================= //

    public static void draw(Object vertexBuffer, int vertexCount) {
        Renderer.getDrawer().draw((VertexBuffer) vertexBuffer, vertexCount);
    }

    public static void drawIndexed(Object vertexBuffer, Object indexBuffer, int indexCount) {
        Renderer.getInstance().getDrawer().drawIndexed(
                (Buffer) vertexBuffer, (IndexBuffer) indexBuffer, indexCount);
    }

    // ========================= //
    // VertexFormatElement       //
    // ========================= //

    public static VertexFormatElement vertexFormatElement(
            int id, int index,
            VertexFormatElement.Type type, VertexFormatElement.Usage usage,
            int count) {
        return new VertexFormatElement(id, index, type, usage, count);
    }

    // ========================= //
    // VertexFormat builder      //
    // ========================= //

    public static VertexFormat buildVertexFormat(String[] names, VertexFormatElement[] elements) {
        VertexFormat.Builder builder = VertexFormat.builder();
        for (int i = 0; i < names.length; i++) {
            builder.add(names[i], elements[i]);
        }
        return builder.build();
    }

    // ========================= //
    // Renderer / SwapChain      //
    // ========================= //

    public static int getSwapChainWidth() {
        return Renderer.getInstance().getSwapChain().getWidth();
    }

    public static int getSwapChainHeight() {
        return Renderer.getInstance().getSwapChain().getHeight();
    }

    public static void beginRenderPass(
            net.vulkanmod.vulkan.framebuffer.RenderPass renderPass,
            net.vulkanmod.vulkan.framebuffer.Framebuffer framebuffer) {
        Renderer.getInstance().beginRenderPass(renderPass, framebuffer);
    }

    /**
     * Rebind the main render target so the composite can draw into it.
     * <p>
     * Without Beryl: restarts MC's swapchain render pass via DefaultMainPass.
     * With Beryl: restarts Beryl's hdrFramebuffer render pass via ShaderMainPass.
     * Both implement the MainPass interface, so this call is polymorphic.
     */
    public static void rebindMainTarget() {
        Renderer.getInstance().getMainPass().rebindMainTarget();
    }

    // ========================= //
    // Uniform setup             //
    // ========================= //

    public static void addUniformWithBuffer(
            net.vulkanmod.vulkan.shader.layout.AlignedStruct.Builder builder,
            String type, String name, int count,
            java.util.function.Supplier<net.vulkanmod.vulkan.util.MappedBuffer> bufferSupplier) {
        net.vulkanmod.vulkan.shader.layout.Uniform.Info info =
                net.vulkanmod.vulkan.shader.layout.Uniform.createUniformInfo(type, name, count);
        info.setBufferSupplier(bufferSupplier);
        builder.addUniformInfo(info);
    }

    // ========================= //
    // Push Constants            //
    // ========================= //

    /**
     * Returns true — push constants are always available on VM 0.6+.
     * Retained as an abstraction point for future VM API changes.
     */
    public static boolean hasPushConstants() {
        return true;
    }

    /**
     * Build a push constant block and attach it to the pipeline builder.
     *
     * @param pipelineBuilder the Pipeline.Builder being constructed
     * @param type   uniform type string (e.g. "float")
     * @param name   uniform name (e.g. "uModelOffset")
     * @param count  element count (e.g. 3 for vec3)
     * @param bufferSupplier supplier for the MappedBuffer backing this uniform
     */
    public static void buildAndSetPushConstants(
            net.vulkanmod.vulkan.shader.Pipeline.Builder pipelineBuilder,
            String type, String name, int count,
            java.util.function.Supplier<net.vulkanmod.vulkan.util.MappedBuffer> bufferSupplier) {
        net.vulkanmod.vulkan.shader.layout.AlignedStruct.Builder pcBuilder =
                new net.vulkanmod.vulkan.shader.layout.AlignedStruct.Builder();
        net.vulkanmod.vulkan.shader.layout.Uniform.Info info =
                net.vulkanmod.vulkan.shader.layout.Uniform.createUniformInfo(type, name, count);
        info.setBufferSupplier(bufferSupplier);
        pcBuilder.addUniformInfo(info);
        try {
            java.lang.reflect.Field pcField =
                    net.vulkanmod.vulkan.shader.Pipeline.Builder.class.getDeclaredField("pushConstants");
            pcField.setAccessible(true);
            pcField.set(pipelineBuilder, pcBuilder.buildPushConstant());
        } catch (Exception e) {
            throw new RuntimeException("[DH-Vulkan] Failed to set push constants on pipeline builder", e);
        }
    }

    /**
     * Per-draw state application: issues vkCmdPushConstants (12 bytes, zero-copy to cmd buffer).
     */
    public static void applyPerDrawState(net.vulkanmod.vulkan.shader.GraphicsPipeline pipeline) {
        Renderer.getInstance().pushConstants(pipeline);
    }

    /**
     * No-op on VM 0.6+ (Info.setBufferSupplier handles supplier resolution).
     * Retained as an abstraction point for future VM API changes.
     */
    public static void setUniformSuppliers(
            net.vulkanmod.vulkan.shader.descriptor.UBO ubo,
            java.util.Map<String, net.vulkanmod.vulkan.util.MappedBuffer> bufferMap) {
        // No-op on VM 0.6+
    }

    // ========================= //
    // GlTexture / Lightmap      //
    // ========================= //

    public static VulkanImage getLightmapVulkanImage() {
        try {
            var lightmapView = net.minecraft.client.Minecraft.getInstance()
                    .gameRenderer.lightTexture().getTextureView();
            if (lightmapView == null) return null;
            com.mojang.blaze3d.opengl.GlTexture glTex =
                    (com.mojang.blaze3d.opengl.GlTexture) lightmapView.texture();
            net.vulkanmod.gl.VkGlTexture vkGlTex =
                    net.vulkanmod.gl.VkGlTexture.getTexture(glTex.glId());
            return vkGlTex != null ? vkGlTex.getVulkanImage() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ========================= //
    // Config value scaling      //
    // ========================= //

    /**
     * DH 1.21.1+ returns noise intensity as a pre-scaled 0-1 float. Pass through.
     */
    public static float scaleNoiseIntensity(float raw) {
        return raw;
    }

    // ========================= //
    // Swapchain access          //
    // ========================= //

    /**
     * Get MC's swapchain depth attachment for depth-compared compositing.
     * Returns the VulkanImage backing MC's depth buffer.
     */
    public static VulkanImage getSwapChainDepthAttachment() {
        return Renderer.getInstance().getSwapChain().getDepthAttachment();
    }

    /**
     * Get the depth attachment from whatever framebuffer MC terrain was rendered into.
     * <p>
     * Without Beryl: this is the swapchain depth (same as getSwapChainDepthAttachment).
     * With Beryl: terrain renders into hdrFramebuffer, so we read from the
     * currently-bound framebuffer's depth attachment.
     */
    public static VulkanImage getMainTargetDepthAttachment() {
        if (!BERYL_ACTIVE) {
            return Renderer.getInstance().getSwapChain().getDepthAttachment();
        }
        // With Beryl, terrain depth lives in the bound framebuffer (hdrFramebuffer)
        net.vulkanmod.vulkan.framebuffer.Framebuffer bound = Renderer.getInstance().getBoundFramebuffer();
        if (bound != null) {
            return bound.getDepthAttachment();
        }
        // Fallback to swapchain
        return Renderer.getInstance().getSwapChain().getDepthAttachment();
    }

    // ========================= //
    // Depth-only sampling       //
    // ========================= //

    /** Cached depth-only image view for D24S8 / D32S8 formats */
    private static long depthOnlyView = 0;
    private static long lastDepthImageId = 0;
    /** Saved original view for restore after draw */
    private static long savedOriginalView = 0;
    private static VulkanImage savedDepthImage = null;

    /**
     * Prepare MC's depth texture for sampling by setting the depth-only
     * image view on combined D+S formats and binding to the given slot.
     * <p>
     * Only creates a depth-only view for combined depth+stencil
     * formats (D24S8, D32S8). For depth-only formats like D32_SFLOAT (Mac),
     * the default mainImageView already has DEPTH_BIT aspect — just bind directly.
     * <p>
     * IMPORTANT: Does NOT restore the original view — you MUST call
     * {@link #restoreMcDepthView()} after the draw completes.
     */
    public static void prepareMcDepthForSampling(int slot, VulkanImage depthImage) {
        int VK_IMAGE_ASPECT_STENCIL_BIT = 4;
        int VK_IMAGE_ASPECT_DEPTH_BIT = 2;

        if ((depthImage.aspect & VK_IMAGE_ASPECT_STENCIL_BIT) != 0) {
            try {
                long imageId = (long) vulkanImageIdField.get(depthImage);

                if (imageId != lastDepthImageId || depthOnlyView == 0) {
                    if (depthOnlyView != 0) {
                        org.lwjgl.vulkan.VK10.vkDestroyImageView(
                                net.vulkanmod.vulkan.Vulkan.getVkDevice(), depthOnlyView, null);
                    }
                    depthOnlyView = VulkanImage.createImageView(
                            imageId, depthImage.format,
                            VK_IMAGE_ASPECT_DEPTH_BIT,
                            1, depthImage.mipLevels);
                    lastDepthImageId = imageId;
                    LOGGER.info("[DH-Vulkan] Created depth-only view for D+S format={} aspect={} (view={})",
                            depthImage.format, depthImage.aspect, depthOnlyView);
                }

                savedOriginalView = (long) vulkanImageViewField.get(depthImage);
                savedDepthImage = depthImage;
                vulkanImageViewField.setLong(depthImage, depthOnlyView);

                VTextureSelector.bindTexture(slot, depthImage);
            } catch (Exception e) {
                LOGGER.error("[DH-Vulkan] Failed to create depth-only view, binding as-is", e);
                savedDepthImage = null;
                VTextureSelector.bindTexture(slot, depthImage);
            }
        } else {
            // D32_SFLOAT or other depth-only formats — just bind directly
            savedDepthImage = null;
            VTextureSelector.bindTexture(slot, depthImage);
        }
    }

    /**
     * Force-set the currentLayout field on a VulkanImage via reflection.
     * <p>
     * VulkanMod's render pass finalLayout transitions update the GPU layout
     * but do NOT update the software-tracked currentLayout field. This causes
     * transitionImageLayout() to skip barriers on subsequent frames.
     */
    public static void forceDepthLayout(VulkanImage depthImage) {
        if (vulkanImageLayoutField == null) return;
        try {
            vulkanImageLayoutField.setInt(depthImage,
                    org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Failed to force depth layout", e);
        }
    }

    /**
     * Transition MC's depth image to SHADER_READ_ONLY_OPTIMAL for sampling.
     */
    public static void transitionDepthForSampling(VulkanImage depthImage) {
        try {
            forceDepthLayout(depthImage);
            org.lwjgl.vulkan.VkCommandBuffer cmd = Renderer.getCommandBuffer();
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                depthImage.transitionImageLayout(stack, cmd,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Failed to transition depth for sampling", e);
        }
    }

    /**
     * Transition MC's depth image back to DEPTH_STENCIL_ATTACHMENT_OPTIMAL
     * after sampling is complete.
     */
    public static void transitionDepthForAttachment(VulkanImage depthImage) {
        try {
            org.lwjgl.vulkan.VkCommandBuffer cmd = Renderer.getCommandBuffer();
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                depthImage.transitionImageLayout(stack, cmd,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Failed to transition depth for attachment", e);
        }
    }

    /**
     * Restore the original mainImageView on the depth image after draw.
     * Must be called after the draw that samples MC depth completes.
     */
    public static void restoreMcDepthView() {
        if (savedDepthImage != null && savedOriginalView != 0) {
            try {
                vulkanImageViewField.setLong(savedDepthImage, savedOriginalView);
            } catch (Exception e) {
                LOGGER.error("[DH-Vulkan] Failed to restore MC depth view", e);
            }
            savedDepthImage = null;
            savedOriginalView = 0;
        }
    }

    /**
     * Convenience: prepare, bind, and restore in one call.
     *
     * @deprecated Use {@link #prepareMcDepthForSampling} +
     *             {@link #restoreMcDepthView} instead
     */
    public static void bindMcDepthForSampling(int slot, VulkanImage depthImage) {
        prepareMcDepthForSampling(slot, depthImage);
        // Note: caller must call restoreMcDepthView() after draw
    }

    // ========================= //
    // MC depth copy for deferred //
    // ========================= //

    /** Persistent depth copy image — recreated on resize */
    private static VulkanImage mcDepthCopyImage = null;
    private static int mcDepthCopyWidth = 0;
    private static int mcDepthCopyHeight = 0;

    /**
     * Copy MC's swapchain depth to a separate image for sampling.
     * <p>
     * Must be called AFTER endRenderPass() (so the depth is not an active
     * attachment) and BEFORE rebindMainTarget() (which re-attaches it).
     */
    public static VulkanImage copyMcDepthForSampling(VulkanImage srcDepth) {
        try {
            // Recreate copy image if dimensions changed
            if (mcDepthCopyImage == null
                    || mcDepthCopyWidth != srcDepth.width
                    || mcDepthCopyHeight != srcDepth.height) {
                if (mcDepthCopyImage != null) {
                    mcDepthCopyImage.free();
                }
                int copyUsage = 0x04 /* VK_IMAGE_USAGE_SAMPLED_BIT */
                        | 0x08 /* VK_IMAGE_USAGE_TRANSFER_DST_BIT */;
                mcDepthCopyImage = VulkanImage.createDepthImage(
                        srcDepth.format, srcDepth.width, srcDepth.height,
                        copyUsage, false, true);
                mcDepthCopyWidth = srcDepth.width;
                mcDepthCopyHeight = srcDepth.height;
                LOGGER.info("[DH-Vulkan] Created MC depth copy image: {}x{} format={}",
                        srcDepth.width, srcDepth.height, srcDepth.format);
            }

            org.lwjgl.vulkan.VkCommandBuffer cmdBuffer = (org.lwjgl.vulkan.VkCommandBuffer) rendererCmdBufferField
                    .get(Renderer.getInstance());

            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                srcDepth.transitionImageLayout(stack, cmdBuffer,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

                mcDepthCopyImage.transitionImageLayout(stack, cmdBuffer,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

                long srcId = (long) vulkanImageIdField.get(srcDepth);
                long dstId = (long) vulkanImageIdField.get(mcDepthCopyImage);

                org.lwjgl.vulkan.VkImageCopy.Buffer copyRegion = org.lwjgl.vulkan.VkImageCopy.calloc(1, stack);
                copyRegion.get(0)
                        .srcSubresource(s -> s
                                .aspectMask(srcDepth.aspect)
                                .mipLevel(0).baseArrayLayer(0).layerCount(1))
                        .srcOffset(o -> o.set(0, 0, 0))
                        .dstSubresource(s -> s
                                .aspectMask(srcDepth.aspect)
                                .mipLevel(0).baseArrayLayer(0).layerCount(1))
                        .dstOffset(o -> o.set(0, 0, 0))
                        .extent(e -> e.set(srcDepth.width, srcDepth.height, 1));

                org.lwjgl.vulkan.VK10.vkCmdCopyImage(cmdBuffer,
                        srcId, org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        dstId, org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        copyRegion);

                mcDepthCopyImage.transitionImageLayout(stack, cmdBuffer,
                        org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }

            return mcDepthCopyImage;
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Failed to copy MC depth", e);
            return srcDepth; // fallback to original (may not work on NVIDIA)
        }
    }

    /** Clean up the persistent depth copy image */
    public static void cleanupDepthCopy() {
        if (mcDepthCopyImage != null) {
            mcDepthCopyImage.free();
            mcDepthCopyImage = null;
        }
        mcDepthCopyWidth = 0;
        mcDepthCopyHeight = 0;
    }

    /**
     * Free all static Vulkan resources held by Compat.
     * Must be called during VulkanRenderDelegate.cleanup() to prevent leaks
     * across server transitions (leave/join world cycles).
     */
    public static void cleanupStaticResources() {
        if (depthOnlyView != 0) {
            org.lwjgl.vulkan.VK10.vkDestroyImageView(
                    net.vulkanmod.vulkan.Vulkan.getVkDevice(), depthOnlyView, null);
            depthOnlyView = 0;
        }
        lastDepthImageId = 0;
        savedOriginalView = 0;
        savedDepthImage = null;

        cleanupDepthCopy();
    }

    // ========================= //
    // Cloud rendering helpers   //
    // ========================= //

    /**
     * Load a resource by path from Minecraft's resource manager.
     */
    public static java.io.InputStream openMcResource(String path) throws java.io.IOException {
        var loc = net.minecraft.resources.Identifier.withDefaultNamespace(path);
        return net.minecraft.client.Minecraft.getInstance().getResourceManager()
                .getResourceOrThrow(loc).open();
    }

    /**
     * Get the cloud render range in blocks.
     */
    public static int getCloudRenderRange() {
        return Math.min(net.minecraft.client.Minecraft.getInstance().options.cloudRange().get(), 128) * 16;
    }

    /**
     * Get cloud pixel data from a NativeImage as int array.
     */
    public static int[] getCloudPixels(com.mojang.blaze3d.platform.NativeImage image) {
        return image.getPixelsABGR();
    }

    /**
     * Get cloud height for the current dimension. Returns -1 if none (e.g. nether).
     */
    public static int getCloudHeight(net.minecraft.client.multiplayer.ClientLevel level) {
        try {
            return level.dimensionType().hasSkyLight() ? 192 : -1;
        } catch (Exception e) {
            return 192;
        }
    }

    /**
     * Get cloud color as RGB float[3] for the current level.
     * API varies across MC 1.21.x sub-versions.
     */
    public static float[] getCloudColorRGB(net.minecraft.client.multiplayer.ClientLevel level, float partialTicks) {
        try {
            int argb = level.environmentAttributes().getValue(
                    net.minecraft.world.attribute.EnvironmentAttributes.CLOUD_COLOR,
                    net.minecraft.core.BlockPos.ZERO);
            cloudColorResult[0] = ((argb >> 16) & 0xFF) / 255.0f;
            cloudColorResult[1] = ((argb >> 8) & 0xFF) / 255.0f;
            cloudColorResult[2] = (argb & 0xFF) / 255.0f;
        } catch (Exception e) {
            cloudColorResult[0] = 1.0f;
            cloudColorResult[1] = 1.0f;
            cloudColorResult[2] = 1.0f;
        }
        return cloudColorResult;
    }

    /**
     * Begin building cloud mesh geometry.
     */
    public static com.mojang.blaze3d.vertex.BufferBuilder beginCloudMesh() {
        return com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
    }

    /**
     * Add a vertex with position + ARGB color to the cloud mesh builder.
     */
    public static void putCloudVertex(com.mojang.blaze3d.vertex.BufferBuilder builder, float x, float y, float z, int color) {
        builder.addVertex(x, y, z).setColor(color);
    }

    /**
     * Finish building the cloud mesh, returning the mesh data (or null if empty).
     */
    public static Object finishCloudMesh(com.mojang.blaze3d.vertex.BufferBuilder builder) {
        return builder.build();
    }

    /**
     * Set VRenderSystem model offset (VM 0.6+ direct call).
     */
    public static void setModelOffset(float x, float y, float z) {
        net.vulkanmod.vulkan.VRenderSystem.setModelOffset(x, y, z);
    }

    /**
     * Get the MeshData class for reflection.
     */
    public static Class<?> getMeshDataClass() {
        try {
            return Class.forName("com.mojang.blaze3d.vertex.MeshData");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static final com.seibel.distanthorizons.core.logging.DhLogger LOGGER = new com.seibel.distanthorizons.core.logging.DhLoggerBuilder()
            .build();

    private Compat() {
    }
}
