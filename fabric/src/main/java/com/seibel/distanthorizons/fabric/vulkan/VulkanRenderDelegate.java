/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    VulkanMod rendering delegate implementation.
 */

package com.seibel.distanthorizons.fabric.vulkan;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.enums.EConfigEntryAppearance;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.core.render.renderer.IVulkanRenderDelegate;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.util.math.Mat4f;
import com.seibel.distanthorizons.core.util.math.Vec3f;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.VertexBuffer;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concrete implementation of {@link IVulkanRenderDelegate} that uses
 * VulkanMod's rendering API to draw DH terrain.
 */
public class VulkanRenderDelegate implements IVulkanRenderDelegate {
    private static final DhLogger LOGGER = new DhLoggerBuilder().build();

    private final VulkanRenderContext renderContext;
    private boolean initialized = false;
    private boolean initFailed = false;

    /** DH-owned framebuffer — LODs render into this instead of MC's render pass */
    private DhVulkanFramebuffer dhFramebuffer;
    /** Composite pipeline — blends DH's framebuffer onto MC's */
    private DhCompositePipeline compositePipeline;

    /** SSAO pipeline — computes and applies ambient occlusion (Phase 7) */
    private DhSsaoPipeline ssaoPipeline;

    /** Fog pipeline — computes and applies distance/height fog (Phase 7) */
    private DhFogPipeline fogPipeline;

    /** Shared index buffer for quad rendering (6 indices per quad) */
    private IndexBuffer quadIndexBuffer;
    private int quadIndexBufferCapacity = 0;

    /**
     * Tracks a cached Vulkan VertexBuffer alongside the identity of the
     * ByteBuffer it was created from, for invalidation when terrain is re-uploaded.
     */
    private static class CachedBuffer {
        final VertexBuffer vkBuffer;
        final int handleIdentity;

        CachedBuffer(VertexBuffer vkBuffer, int handleIdentity) {
            this.vkBuffer = vkBuffer;
            this.handleIdentity = handleIdentity;
        }

        void free() {
            this.vkBuffer.scheduleFree();
        }
    }

    /**
     * Cache of uploaded Vulkan vertex buffers, keyed by GLVertexBuffer identity
     * hash.
     * <p>
     * Entries are removed in two ways:
     * 1. When drawBuffer() detects vulkanBufferHandle changed or became null
     * (DH called LodBufferContainer.close() which nulls the handle)
     * 2. When freeBufferForVbo() is called explicitly from
     * LodBufferContainer.close()
     * 3. When cleanup() is called on world unload
     */
    private final Map<Integer, CachedBuffer> vulkanBufferCache = new ConcurrentHashMap<>();

    /** Saved VRenderSystem state — restored in endFrame() */
    private boolean savedCullState;
    private boolean savedDepthMask;
    private int savedDepthFun;
    private int savedTopology;
    private int savedPolygonMode;
    // Saved blend state (all 6 fields)
    private boolean savedBlendEnabled;
    private int savedBlendSrcRgb;
    private int savedBlendDstRgb;
    private int savedBlendSrcAlpha;
    private int savedBlendDstAlpha;
    private int savedBlendOp;

    public VulkanRenderDelegate() {
        this.renderContext = VulkanRenderContext.getInstance();
    }

    @Override
    public void init() {
        if (this.initialized || this.initFailed) {
            return;
        }

        try {
            // Lock/hide settings unsupported on the Vulkan path
            disableUnsupportedSettings();

            this.renderContext.init();
            this.ensureQuadIndexBuffer(65536);

            // Initialize DH framebuffer matching MC's viewport
            int width = Renderer.getInstance().getSwapChain().getWidth();
            int height = Renderer.getInstance().getSwapChain().getHeight();
            this.dhFramebuffer = new DhVulkanFramebuffer();
            this.dhFramebuffer.init(width, height);

            // Initialize composite pipeline
            this.compositePipeline = new DhCompositePipeline();
            this.compositePipeline.init();

            // Initialize SSAO pipeline (Phase 7)
            this.ssaoPipeline = new DhSsaoPipeline();
            this.ssaoPipeline.init(width, height);

            // Initialize Fog pipeline (Phase 7)
            this.fogPipeline = new DhFogPipeline();
            this.fogPipeline.init(width, height);

            this.initialized = true;
            LOGGER.info("[DH-Vulkan] VulkanRenderDelegate initialized.");
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] VulkanRenderDelegate init failed — LODs will not render", e);
            this.initFailed = true;
        }
    }

    private void ensureQuadIndexBuffer(int quadCount) {
        if (quadCount <= this.quadIndexBufferCapacity) {
            return;
        }

        if (this.quadIndexBuffer != null) {
            this.quadIndexBuffer.scheduleFree();
        }

        int indexCount = quadCount * 6;
        ByteBuffer indexData = ByteBuffer.allocateDirect(indexCount * 4);
        indexData.order(ByteOrder.nativeOrder());
        for (int i = 0; i < quadCount; i++) {
            int base = i * 4;
            indexData.putInt(base + 0);
            indexData.putInt(base + 1);
            indexData.putInt(base + 2);
            indexData.putInt(base + 2);
            indexData.putInt(base + 3);
            indexData.putInt(base + 0);
        }
        indexData.flip();

        this.quadIndexBuffer = new IndexBuffer(indexData.remaining(), MemoryTypes.HOST_MEM,
                IndexBuffer.IndexType.UINT32);
        this.quadIndexBuffer.copyBuffer(indexData, indexData.remaining());
        this.quadIndexBufferCapacity = quadCount;

    }

    @Override
    public void beginFrame() {
        if (!this.initialized) {
            this.init();
        }
        if (this.initFailed) {
            return;
        }

        // Save and override VulkanMod render state for DH rendering
        this.savedCullState = VRenderSystem.cull;
        this.savedDepthMask = VRenderSystem.depthMask;
        this.savedDepthFun = VRenderSystem.depthFun;
        this.savedTopology = VRenderSystem.topology;
        this.savedPolygonMode = VRenderSystem.polygonMode;
        this.savedBlendEnabled = PipelineState.blendInfo.enabled;
        this.savedBlendSrcRgb = PipelineState.blendInfo.srcRgbFactor;
        this.savedBlendDstRgb = PipelineState.blendInfo.dstRgbFactor;
        this.savedBlendSrcAlpha = PipelineState.blendInfo.srcAlphaFactor;
        this.savedBlendDstAlpha = PipelineState.blendInfo.dstAlphaFactor;
        this.savedBlendOp = PipelineState.blendInfo.blendOp;

        VRenderSystem.cull = true; // Back-face culling — halves fragment count
        VRenderSystem.depthTest = true; // Ensure Early-Z is active
        VRenderSystem.depthMask = true; // LODs need to write depth
        VRenderSystem.depthFun = 515; // GL_LEQUAL
        VRenderSystem.topology = 3; // VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
        VRenderSystem.polygonMode = 0; // VK_POLYGON_MODE_FILL
        PipelineState.blendInfo.enabled = false; // Opaque LODs don't need blending

        // No polygon offset needed — we render to our own framebuffer now,
        // and the composite step handles depth comparison with MC terrain.

        // Bind MC's lightmap texture to slot 2.
        // Cast to GlTexture (vanilla MC class) to get the GL ID, then resolve
        // through VkGlTexture → VulkanImage (same data VulkanMod's terrain uses).
        try {
            var lightmapView = Minecraft.getInstance().gameRenderer.lightTexture().getTextureView();
            if (lightmapView != null) {
                com.mojang.blaze3d.opengl.GlTexture glTex = (com.mojang.blaze3d.opengl.GlTexture) lightmapView
                        .texture();
                net.vulkanmod.gl.VkGlTexture vkGlTex = net.vulkanmod.gl.VkGlTexture.getTexture(glTex.glId());
                if (vkGlTex != null && vkGlTex.getVulkanImage() != null) {
                    VTextureSelector.setLightTexture(vkGlTex.getVulkanImage());
                }
            }
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Failed to bind MC lightmap", e);
        }

        // Switch from MC's render pass to DH's own framebuffer
        Renderer.getInstance().endRenderPass();
        this.dhFramebuffer.beginRenderPass();

        this.renderContext.bindTerrainPipeline();
    }

    @Override
    public void fillUniformData(DhApiRenderParam renderParameters) {
        if (this.initFailed) {
            return;
        }

        // Combined projection * model-view matrix
        // IMPORTANT: Use MC's projection matrix (not DH's) so LOD depth values
        // are compatible with MC's depth buffer. DH's projection has a much larger
        // far plane which would make LOD depth values SMALLER (closer) than MC terrain,
        // causing LODs to incorrectly render in front of MC chunks.
        Mat4f combinedMatrix = new Mat4f(renderParameters.mcProjectionMatrix);
        combinedMatrix.multiply(renderParameters.dhModelViewMatrix);
        this.renderContext.setUniformMat4("uCombinedMatrix", combinedMatrix);

        // World Y offset
        this.renderContext.setUniformFloat("uWorldYOffset", (float) renderParameters.worldYOffset);

        // Micro offset (prevents z-fighting)
        this.renderContext.setUniformFloat("uMircoOffset", 0.01f);

        // Earth curvature
        float curveRatio = Config.Client.Advanced.Graphics.Experimental.earthCurveRatio.get();
        if (curveRatio < -1.0f || curveRatio > 1.0f) {
            curveRatio = 6371000.0f / curveRatio;
        } else {
            curveRatio = 0.0f;
        }
        this.renderContext.setUniformFloat("uEarthRadius", curveRatio);

        // Clip distance — prevents LODs from rendering where MC terrain
        // exists. This avoids double-rendering of transparent blocks (water,
        // leaves, glass) in the near zone where both MC and DH render the
        // same block. Beyond clip distance, the composite depth test handles
        // occlusion against MC's opaque terrain.
        float dhNearClipDistance = RenderUtil.getNearClipPlaneInBlocks();
        this.renderContext.setUniformFloat("uClipDistance", dhNearClipDistance);

        // Dither
        this.renderContext.setUniformBool("uDitherDhRendering",
                Config.Client.Advanced.Graphics.Quality.ditherDhFade.get());

        // Noise
        this.renderContext.setUniformBool("uNoiseEnabled",
                Config.Client.Advanced.Graphics.NoiseTexture.enableNoiseTexture.get());
        this.renderContext.setUniformInt("uNoiseSteps",
                Config.Client.Advanced.Graphics.NoiseTexture.noiseSteps.get());
        this.renderContext.setUniformFloat("uNoiseIntensity",
                Config.Client.Advanced.Graphics.NoiseTexture.noiseIntensity.get());
        this.renderContext.setUniformInt("uNoiseDropoff",
                Config.Client.Advanced.Graphics.NoiseTexture.noiseDropoff.get());

        // Debug
        this.renderContext.setUniformBool("uIsWhiteWorld",
                Config.Client.Advanced.Debugging.enableWhiteWorld.get());

        // Model offset starts at origin — updated per-buffer via setModelOffset()
        this.renderContext.setUniformVec3f("uModelOffset", new Vec3f(0, 0, 0));

        // Bind UBOs + descriptors after setting all uniforms
        this.renderContext.uploadAndBindUBOs();
    }

    @Override
    public void setModelOffset(Vec3f modelOffset) {
        if (this.initFailed) {
            return;
        }

        // Update the model offset uniform and re-bind UBOs
        this.renderContext.setUniformVec3f("uModelOffset", modelOffset);
        this.renderContext.uploadAndBindUBOs();
    }

    @Override
    public long uploadVertexData(ByteBuffer vertexData, int vertexCount) {
        // No-op — this method is not called by DH core.
        // If it were, we'd need to cache the returned buffer for later cleanup.
        return 0;
    }

    @Override
    public void drawBuffer(GLVertexBuffer vbo, int indexCount) {
        if (this.initFailed || indexCount <= 0) {
            return;
        }

        int vboId = System.identityHashCode(vbo);
        Object handle = vbo.vulkanBufferHandle;

        // DH has cleaned up this VBO (LodBufferContainer.close() nulls the handle).
        // Free our cached Vulkan buffer immediately.
        if (handle == null) {
            CachedBuffer stale = this.vulkanBufferCache.remove(vboId);
            if (stale != null) {
                stale.free();
            }
            return;
        }

        try {
            int handleId = System.identityHashCode(handle);
            CachedBuffer cached = this.vulkanBufferCache.get(vboId);

            // Invalidate if the ByteBuffer handle changed (terrain was re-uploaded)
            if (cached != null && cached.handleIdentity != handleId) {
                cached.free();
                this.vulkanBufferCache.remove(vboId);
                cached = null;
            }

            if (cached == null && handle instanceof ByteBuffer) {
                ByteBuffer vertexData = (ByteBuffer) handle;
                int dataSize = vertexData.remaining();

                if (dataSize <= 0) {
                    return;
                }

                VertexBuffer vkBuffer = new VertexBuffer(dataSize, MemoryTypes.GPU_MEM);
                vertexData.position(0);
                vkBuffer.copyBuffer(vertexData, dataSize);
                vertexData.position(0);

                cached = new CachedBuffer(vkBuffer, handleId);
                this.vulkanBufferCache.put(vboId, cached);
            }

            if (cached == null) {
                return;
            }

            // Ensure index buffer is large enough
            int quadCount = indexCount / 6;
            if (quadCount > this.quadIndexBufferCapacity) {
                this.ensureQuadIndexBuffer(quadCount + 1024);
            }

            // THE draw call
            this.renderContext.drawIndexed(cached.vkBuffer, this.quadIndexBuffer, indexCount);

        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Error during drawBuffer: {}", e.getMessage());
        }
    }

    /**
     * Frees the cached Vulkan VertexBuffer for a given GLVertexBuffer.
     * Called from LodBufferContainer.close() when DH destroys a VBO.
     * This is the primary cleanup path — ensures GPU memory is freed
     * deterministically without relying on GC.
     */
    @Override
    public void freeBuffer(GLVertexBuffer vbo) {
        int vboId = System.identityHashCode(vbo);
        CachedBuffer cached = this.vulkanBufferCache.remove(vboId);
        if (cached != null) {
            cached.free();
        }
    }

    @Override
    public void setBlendState(boolean enabled) {
        PipelineState.blendInfo.enabled = enabled;
        if (enabled) {
            // Match GL path: glBlendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE,
            // ONE_MINUS_SRC_ALPHA)
            PipelineState.blendInfo.srcRgbFactor = 6; // VK_BLEND_FACTOR_SRC_ALPHA
            PipelineState.blendInfo.dstRgbFactor = 7; // VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
            PipelineState.blendInfo.srcAlphaFactor = 1; // VK_BLEND_FACTOR_ONE
            PipelineState.blendInfo.dstAlphaFactor = 7; // VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
            PipelineState.blendInfo.blendOp = 0; // VK_BLEND_OP_ADD
        }
        // Re-bind pipeline so VulkanMod picks up the new blend state
        this.renderContext.bindTerrainPipeline();
    }

    @Override
    public void endFrame(DhApiRenderParam renderParam) {
        // End DH's render pass — this transitions the color+depth attachments
        // to SHADER_READ_ONLY_OPTIMAL for sampling in post-process + composite.
        Renderer.getInstance().endRenderPass();

        // Phase 7: SSAO post-process (between LOD render and composite)
        if (this.ssaoPipeline != null && Config.Client.Advanced.Graphics.Ssao.enableSsao.get()) {
            try {
                this.ssaoPipeline.render(this.dhFramebuffer,
                        new com.seibel.distanthorizons.core.util.math.Mat4f(renderParam.mcProjectionMatrix));
            } catch (Exception e) {
                LOGGER.error("[DH-Vulkan] SSAO render failed", e);
            }
        }

        // Phase 7: Fog post-process (after SSAO, before composite)
        if (this.fogPipeline != null
                && Config.Client.Advanced.Graphics.Fog.enableDhFog.get()) {
            try {
                this.fogPipeline.render(this.dhFramebuffer,
                        new com.seibel.distanthorizons.core.util.math.Mat4f(renderParam.dhModelViewMatrix),
                        new com.seibel.distanthorizons.core.util.math.Mat4f(renderParam.mcProjectionMatrix),
                        renderParam.partialTicks);
            } catch (Exception e) {
                LOGGER.error("[DH-Vulkan] Fog render failed", e);
            }
        }

        // Composite DH's framebuffer onto MC's render target BEFORE MC renders
        // terrain. This is the correct ordering because:
        // 1. MC's depth buffer is still ~1.0 here → all LODs pass depth test
        // 2. LOD depth is written into MC's depth buffer
        // 3. MC opaque terrain then renders ON TOP (MC depth < LOD depth → overwrites)
        // 4. MC transparent terrain (water) renders ON TOP with alpha blending,
        // so LODs are visible through water — exactly like vanilla MC rendering
        Renderer.getInstance().endRenderPass();
        ((DefaultMainPass) Renderer.getInstance().getMainPass()).rebindMainTarget();

        if (this.compositePipeline != null && this.dhFramebuffer != null) {
            int debugMode = Config.Client.Advanced.Debugging.vulkanDebugMode.get();
            VulkanImage ssaoTex = this.ssaoPipeline != null ? this.ssaoPipeline.getIntermediateTexture() : null;
            VulkanImage fogTex = this.fogPipeline != null ? this.fogPipeline.getIntermediateTexture() : null;

            Mat4f invProj = new Mat4f(renderParam.mcProjectionMatrix);
            invProj.invert();
            float[] invProjArray = new float[] {
                    invProj.m00, invProj.m10, invProj.m20, invProj.m30,
                    invProj.m01, invProj.m11, invProj.m21, invProj.m31,
                    invProj.m02, invProj.m12, invProj.m22, invProj.m32,
                    invProj.m03, invProj.m13, invProj.m23, invProj.m33
            };

            this.compositePipeline.render(
                    this.dhFramebuffer.getFramebuffer().getColorAttachment(),
                    this.dhFramebuffer.getFramebuffer().getDepthAttachment(),
                    ssaoTex, fogTex,
                    debugMode, invProjArray);
        }

        // Restore VulkanMod render state (so MC can render normally after this)
        VRenderSystem.cull = this.savedCullState;
        VRenderSystem.depthMask = this.savedDepthMask;
        VRenderSystem.depthFun = this.savedDepthFun;
        VRenderSystem.topology = this.savedTopology;
        VRenderSystem.polygonMode = this.savedPolygonMode;
        PipelineState.blendInfo.enabled = this.savedBlendEnabled;
        PipelineState.blendInfo.srcRgbFactor = this.savedBlendSrcRgb;
        PipelineState.blendInfo.dstRgbFactor = this.savedBlendDstRgb;
        PipelineState.blendInfo.srcAlphaFactor = this.savedBlendSrcAlpha;
        PipelineState.blendInfo.dstAlphaFactor = this.savedBlendDstAlpha;
        PipelineState.blendInfo.blendOp = this.savedBlendOp;
    }

    @Override
    public void deferredComposite(DhApiRenderParam renderParam) {
        // No-op: composite now happens in endFrame() BEFORE MC terrain rendering.
        // This ensures MC opaque terrain overwrites LODs via depth test, and MC
        // transparent terrain (water, leaves) blends on top of LODs correctly.
    }

    @Override
    public void cleanup() {
        LOGGER.info("[DH-Vulkan] cleanup() called, freeing {} cached Vulkan buffers.", this.vulkanBufferCache.size());
        for (CachedBuffer cached : this.vulkanBufferCache.values()) {
            cached.free();
        }
        this.vulkanBufferCache.clear();

        if (this.quadIndexBuffer != null) {
            this.quadIndexBuffer.scheduleFree();
            this.quadIndexBuffer = null;
        }
        if (this.ssaoPipeline != null) {
            this.ssaoPipeline.cleanup();
            this.ssaoPipeline = null;
        }
        if (this.fogPipeline != null) {
            this.fogPipeline.cleanup();
            this.fogPipeline = null;
        }
        if (this.compositePipeline != null) {
            this.compositePipeline.cleanup();
            this.compositePipeline = null;
        }
        if (this.dhFramebuffer != null) {
            this.dhFramebuffer.cleanup();
            this.dhFramebuffer = null;
        }
        this.renderContext.cleanup();
        this.initialized = false;
        LOGGER.info("[DH-Vulkan] VulkanRenderDelegate cleaned up.");
    }

    /**
     * Lock or hide config settings that are unsupported on the Vulkan path.
     * - Wireframe/debug wireframe: visible but locked (planned for future)
     * - Instance rendering, OpenGL, vanilla fog: hidden from UI
     */
    private void disableUnsupportedSettings() {
        // Visible but locked — these are planned features
        Config.Client.Advanced.Debugging.renderWireframe.setApiValue(false);
        Config.Client.Advanced.Debugging.DebugWireframe.enableRendering.setApiValue(false);
        Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue.setApiValue(false);
        Config.Client.Advanced.Debugging.DebugWireframe.showNetworkSyncOnLoadQueue.setApiValue(false);
        Config.Client.Advanced.Debugging.DebugWireframe.showRenderSectionStatus.setApiValue(false);
        Config.Client.Advanced.Debugging.DebugWireframe.showRenderSectionToggling.setApiValue(false);
        Config.Client.Advanced.Debugging.DebugWireframe.showQuadTreeRenderStatus.setApiValue(false);
        Config.Client.Advanced.Debugging.DebugWireframe.showFullDataUpdateStatus.setApiValue(false);

        // Hidden — not applicable to Vulkan
        Config.Client.Advanced.Graphics.GenericRendering.enableInstancedRendering
                .setAppearance(EConfigEntryAppearance.ONLY_IN_FILE);
        Config.Client.Advanced.Graphics.Fog.enableVanillaFog
                .setAppearance(EConfigEntryAppearance.ONLY_IN_FILE);
        Config.Client.Advanced.Debugging.OpenGl.overrideVanillaGLLogger
                .setAppearance(EConfigEntryAppearance.ONLY_IN_FILE);
        Config.Client.Advanced.Debugging.OpenGl.onlyLogGlErrorsOnce
                .setAppearance(EConfigEntryAppearance.ONLY_IN_FILE);
        Config.Client.Advanced.Debugging.OpenGl.glErrorHandlingMode
                .setAppearance(EConfigEntryAppearance.ONLY_IN_FILE);
        Config.Client.Advanced.Debugging.OpenGl.glUploadMode
                .setAppearance(EConfigEntryAppearance.ONLY_IN_FILE);
    }
}
