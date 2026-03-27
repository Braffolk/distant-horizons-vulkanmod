package com.braffolk.dhvulkan.api;

import com.braffolk.dhvulkan.core.VulkanBackend;
import com.braffolk.dhvulkan.core.data.RenderUniforms;
import com.braffolk.dhvulkan.core.data.VkVertexData;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.util.math.Vec3f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.*;

/**
 * Vulkan implementation of DH 3.0's render API definition.
 * Replaces the default OpenGL renderer by binding into DH's
 * SingletonInjector via {@link #bindRenderers()}.
 *
 * Our Vulkan engine handles SSAO, fog, and composite internally
 * as post-process passes, so those renderers are stubs that delegate
 * to the core VulkanBackend at the right lifecycle points.
 */
public class VkRenderApiDefinition extends AbstractDhRenderApiDefinition {

    private final VulkanBackend backend;
    private final VkMetaRenderer metaRenderer;
    private final VkTerrainRenderer terrainRenderer;
    private final VkSsaoRenderer ssaoRenderer;
    private final VkFogRenderer fogRenderer;
    private final VkFarFadeRenderer farFadeRenderer;
    private final VkVanillaFadeRenderer vanillaFadeRenderer;
    private final VkDebugWireframeRenderer debugWireframeRenderer;
    private final VkTestTriangleRenderer testTriangleRenderer;

    public VkRenderApiDefinition(VulkanBackend backend) {
        this.backend = backend;
        this.metaRenderer = new VkMetaRenderer(backend);
        this.terrainRenderer = new VkTerrainRenderer(backend);
        this.ssaoRenderer = new VkSsaoRenderer();
        this.fogRenderer = new VkFogRenderer();
        this.farFadeRenderer = new VkFarFadeRenderer();
        this.vanillaFadeRenderer = new VkVanillaFadeRenderer();
        this.debugWireframeRenderer = new VkDebugWireframeRenderer();
        this.testTriangleRenderer = new VkTestTriangleRenderer();
        // Do NOT call backend.init() here — VulkanMod's VkDevice isn't ready yet.
        // Init is deferred to the first runRenderPassSetup() call.
    }

    @Override public String getApiName() { return "VulkanMod"; }

    // Singletons
    @Override public IDhMetaRenderer getMetaRenderer() { return metaRenderer; }
    @Override public IDhTerrainRenderer getTerrainRenderer() { return terrainRenderer; }
    @Override public IDhSsaoRenderer getSsaoRenderer() { return ssaoRenderer; }
    @Override public IDhFogRenderer getFogRenderer() { return fogRenderer; }
    @Override public IDhFarFadeRenderer getFarFadeRenderer() { return farFadeRenderer; }
    @Override public AbstractDebugWireframeRenderer getDebugWireframeRenderer() { return debugWireframeRenderer; }
    @Override public IDhVanillaFadeRenderer getVanillaFadeRenderer() { return vanillaFadeRenderer; }
    @Override public IDhTestTriangleRenderer getTestTriangleRenderer() { return testTriangleRenderer; }

    // Factories
    @Override public IDhGenericRenderer createGenericRenderer() { return new VkGenericRenderer(); }
    @Override public IVertexBufferWrapper createVboWrapper(String name) { return new VkVertexBufferWrapper(backend); }
    @Override public ILodContainerUniformBufferWrapper createLodContainerUniformWrapper() { return new VkLodContainerUniformWrapper(); }
    @Override public IDhGenericObjectVertexBufferContainer createGenericVboContainer() { return new VkGenericObjectVboContainer(); }

    VkMetaRenderer getVkMetaRenderer() { return metaRenderer; }

    // =========================================== //
    // Helper: convert RenderParams to RenderUniforms
    // =========================================== //

    static RenderUniforms toUniforms(RenderParams params, RenderUniforms target) {
        target.set((com.seibel.distanthorizons.core.util.math.Mat4f) (Object) params.dhProjectionMatrix,
              (com.seibel.distanthorizons.core.util.math.Mat4f) (Object) params.dhModelViewMatrix,
              (com.seibel.distanthorizons.core.util.math.Mat4f) (Object) params.mcProjectionMatrix);
        target.worldYOffset = params.worldYOffset;
        target.partialTicks = params.partialTicks;
        return target;
    }

    // =========================================== //
    // Inner renderer implementations
    // =========================================== //

    /**
     * Meta renderer: handles frame setup, cleanup, composite, and depth/color clear.
     * This is the main lifecycle manager connecting DH's render loop to our Vulkan engine.
     */
    static class VkMetaRenderer implements IDhMetaRenderer {
        private final VulkanBackend backend;
        private final RenderUniforms cachedUniforms = new RenderUniforms();
        private boolean frameActive = false;
        private boolean initialized = false;

        VkMetaRenderer(VulkanBackend backend) {
            this.backend = backend;
        }

        @Override
        public void runRenderPassSetup(RenderParams renderParams) {
            // Deferred init: VulkanMod's VkDevice is only ready at render time
            if (!initialized) {
                backend.init();
                initialized = true;
            }
            toUniforms(renderParams, this.cachedUniforms);
            backend.beginFrame();
            backend.fillUniforms(this.cachedUniforms);
            this.frameActive = true;

            // Set up the late composite hook for SINGLE/DOUBLE Phase 2.
            // MixinLevelRenderer fires at renderLevel @RETURN → Compat.runLateCompositeHook().
            // In DH 2.4, this hook is set by MixinLodRenderer. In DH 3.0, the 2.4 mixin
            // doesn't fire, so we set it here to ensure Phase 2 + clouds work in both paths.
            // Note: no frameActive guard — runRenderPassCleanup sets it false before this fires.
            // VulkanRenderEngine.lateComposite() has its own frameReady guard.
            com.braffolk.dhvulkan.compat.Compat.setLateCompositeHook(() -> {
                toUniforms(renderParams, this.cachedUniforms);
                backend.lateComposite(this.cachedUniforms);
            });
        }

        @Override
        public void runRenderPassCleanup(RenderParams renderParams) {
            if (!frameActive) return;
            toUniforms(renderParams, this.cachedUniforms);
            backend.endFrame(this.cachedUniforms);
            this.frameActive = false;
        }

        @Override
        public void applyToMcTexture(RenderParams renderParams) {
            // Called at the end to composite DH's framebuffer onto MC's render target
            toUniforms(renderParams, this.cachedUniforms);
            backend.deferredComposite(this.cachedUniforms);
        }

        @Override
        public void clearDhDepthAndColorTextures(RenderParams renderParams) {
            // Our framebuffer is cleared at the start of each frame in beginFrame().
            // This is a no-op here since VulkanBackend handles it internally.
        }

    }

    /**
     * Terrain renderer: draws LOD vertex buffers.
     * DH 3.0 passes LodBufferContainers which each hold VBO arrays.
     * We iterate them and draw through VulkanBackend, matching the GL
     * reference implementation's logic.
     */
    static class VkTerrainRenderer implements IDhTerrainRenderer {
        private final VulkanBackend backend;

        // Cached to avoid per-container allocation in the render loop
        private final Vec3f reusableModelPos = new Vec3f(0, 0, 0);

        // Cached reflection fields for VBO arrays in LodBufferContainer.
        // DH 3.0: vboOpaqueWrappers / vboTransparentWrappers
        // [DH 2.4 COMPAT]: vbos / vbosTransparent
        private static java.lang.reflect.Field vbosField;
        private static java.lang.reflect.Field vbosTransparentField;
        private static boolean reflectionResolved = false;

        VkTerrainRenderer(VulkanBackend backend) {
            this.backend = backend;
        }

        private static void resolveFields() {
            if (reflectionResolved) return;
            // DH 3.0 field names (preferred)
            try {
                vbosField = LodBufferContainer.class.getDeclaredField("vboOpaqueWrappers");
                vbosField.setAccessible(true);
                vbosTransparentField = LodBufferContainer.class.getDeclaredField("vboTransparentWrappers");
                vbosTransparentField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                // [DH 2.4 COMPAT] Old field names — remove this catch block when dropping 2.4
                try {
                    vbosField = LodBufferContainer.class.getDeclaredField("vbos");
                    vbosField.setAccessible(true);
                    vbosTransparentField = LodBufferContainer.class.getDeclaredField("vbosTransparent");
                    vbosTransparentField.setAccessible(true);
                } catch (NoSuchFieldException e2) {
                    throw new RuntimeException("[DH-VulkanMod] LodBufferContainer missing vbos field (tried both 2.4 and 3.0 names)", e2);
                }
            }
            reflectionResolved = true;
        }

        private static Object[] getVbos(LodBufferContainer container, boolean opaque) {
            resolveFields();
            try {
                return (Object[]) (opaque ? vbosField.get(container) : vbosTransparentField.get(container));
            } catch (IllegalAccessException e) {
                throw new RuntimeException("[DH-VulkanMod] Failed to read vbos", e);
            }
        }

        @Override
        public void render(RenderParams renderEventParam, boolean opaquePass,
                           SortedArraySet<LodBufferContainer> bufferContainers,
                           IProfilerWrapper profiler) {

            backend.setBlendState(!opaquePass);

            if (bufferContainers == null) return;

            for (int lodIndex = 0; lodIndex < bufferContainers.size(); lodIndex++) {
                LodBufferContainer container = bufferContainers.get(lodIndex);

                // Compute model offset relative to camera (matches GL reference)
                com.seibel.distanthorizons.core.util.math.Vec3d camPos = renderEventParam.exactCameraPosition;
                if (camPos != null) {
                    this.reusableModelPos.set(
                        (float) (container.minCornerBlockPos.getX() - camPos.x),
                        (float) (container.minCornerBlockPos.getY() - camPos.y),
                        (float) (container.minCornerBlockPos.getZ() - camPos.z));
                    backend.setModelOffset(this.reusableModelPos);
                }

                // Use reflection to access vbos — field type differs between DH versions
                Object[] vertexBuffers = getVbos(container, opaquePass);
                if (vertexBuffers == null) continue;

                for (int vboIndex = 0; vboIndex < vertexBuffers.length; vboIndex++) {
                    Object vboObj = vertexBuffers[vboIndex];
                    if (!(vboObj instanceof VkVertexBufferWrapper)) continue;
                    VkVertexBufferWrapper vkVbo = (VkVertexBufferWrapper) vboObj;

                    VkVertexData data = vkVbo.getVertexData();
                    if (data == null) continue;

                    // 4 vertices per face, 6 indices per face = multiply by 1.5
                    int indexCount = (int) (vkVbo.getIndexCount() * 1.5);
                    if (indexCount == 0) continue;

                    backend.drawVertexData(data, indexCount);
                }
            }
        }
    }

    // Post-process renderers are no-ops: our engine handles SSAO, fog, fade internally
    // during endFrame() and deferredComposite().

    static class VkSsaoRenderer implements IDhSsaoRenderer {
        @Override public void render(RenderParams renderParams) { /* handled internally by VulkanBackend */ }
    }

    static class VkFogRenderer implements IDhFogRenderer {
        @Override public void render(RenderParams renderParams) { /* handled internally by VulkanBackend */ }
    }

    static class VkFarFadeRenderer implements IDhFarFadeRenderer {
        @Override public void render(RenderParams renderParams) { /* handled internally by VulkanBackend */ }
    }

    static class VkVanillaFadeRenderer implements IDhVanillaFadeRenderer {
        @Override public void render(RenderParams renderParams) { /* handled internally by VulkanBackend */ }
    }

    static class VkTestTriangleRenderer implements IDhTestTriangleRenderer {
        @Override public void render(RenderParams renderParams) { /* not used */ }
    }

    static class VkDebugWireframeRenderer extends AbstractDebugWireframeRenderer {
        @Override
        public void renderBox(Box box) {
            // Debug wireframe rendering is not supported in Vulkan yet
        }
    }

    static class VkGenericRenderer implements IDhGenericRenderer {
        @Override
        public void render(RenderParams renderEventParam, IProfilerWrapper profiler, boolean renderingWithSsao) {
            // Generic object rendering not yet implemented for Vulkan
        }

        @Override
        public String getVboRenderDebugMenuString() {
            return "VK: 0";
        }

        @Override
        public void add(IDhApiRenderableBoxGroup cubeGroup) throws IllegalArgumentException {
            // Not yet implemented
        }

        @Override
        public IDhApiRenderableBoxGroup remove(long id) {
            return null; // Not yet implemented
        }
    }
}
