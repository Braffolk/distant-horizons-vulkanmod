/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    VulkanMod rendering delegate implementation.
 */

package com.seibel.distanthorizons.fabric.vulkan;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.config.Config;
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

import java.lang.ref.WeakReference;
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

    /** Shared index buffer for quad rendering (6 indices per quad) */
    private IndexBuffer quadIndexBuffer;
    private int quadIndexBufferCapacity = 0;

    /**
     * Tracks a cached Vulkan VertexBuffer alongside the identity of the
     * ByteBuffer it was created from, for invalidation when terrain is re-uploaded.
     * Also holds a WeakReference to the owning GLVertexBuffer for stale detection.
     */
    private static class CachedBuffer {
        final VertexBuffer vkBuffer;
        final int handleIdentity;
        final WeakReference<GLVertexBuffer> ownerRef;

        CachedBuffer(VertexBuffer vkBuffer, int handleIdentity, GLVertexBuffer owner) {
            this.vkBuffer = vkBuffer;
            this.handleIdentity = handleIdentity;
            this.ownerRef = new WeakReference<>(owner);
        }

        /** @return true if the owning VBO has been garbage-collected */
        boolean isStale() {
            return this.ownerRef.get() == null;
        }

        void free() {
            this.vkBuffer.scheduleFree();
        }
    }

    /**
     * Cache of uploaded Vulkan vertex buffers, keyed by GLVertexBuffer identity
     * hash.
     * Stale entries (whose VBO has been GC'd) are swept each frame in beginFrame(),
     * properly freeing GPU memory via scheduleFree().
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

        // Sweep stale cache entries — VBOs that DH has destroyed and GC'd.
        // Must call scheduleFree() since VulkanMod doesn't auto-free on Java GC.
        Iterator<Map.Entry<Integer, CachedBuffer>> it = this.vulkanBufferCache.entrySet().iterator();
        while (it.hasNext()) {
            CachedBuffer cached = it.next().getValue();
            if (cached.isStale()) {
                cached.free();
                it.remove();
            }
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

        // Clip distance — use DH's overdraw-based calculation.
        // Now that we use MC's projection matrix, LOD depth values are compatible
        // with MC's depth buffer, so overdraw produces a smooth transition.
        // Clip distance — prevents LODs from rendering where MC terrain
        // exists. This avoids double-rendering of transparent blocks (water,
        // leaves, glass) in the near zone. The composite depth bias handles
        // opaque blocks beyond this distance.
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

        Object handle = vbo.vulkanBufferHandle;
        if (handle == null) {
            return;
        }

        try {
            int vboId = System.identityHashCode(vbo);
            int handleId = System.identityHashCode(handle);

            CachedBuffer cached = this.vulkanBufferCache.get(vboId);

            // Invalidate if the ByteBuffer handle changed (terrain was re-uploaded)
            if (cached != null && cached.handleIdentity != handleId) {
                cached.free();
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

                cached = new CachedBuffer(vkBuffer, handleId, vbo);
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
    public void endFrame() {
        // End DH's render pass — this transitions the color+depth attachments
        // to SHADER_READ_ONLY_OPTIMAL for sampling in the composite step.
        Renderer.getInstance().endRenderPass();

        // Rebind MC's main render pass so we can composite onto it.
        // DefaultMainPass.rebindMainTarget() handles starting an auxiliary
        // render pass with LOAD_OP_LOAD (preserving MC's existing content).
        ((DefaultMainPass) Renderer.getInstance().getMainPass()).rebindMainTarget();

        // Composite DH's framebuffer onto MC's render target
        this.compositePipeline.render(
                this.dhFramebuffer.getFramebuffer().getColorAttachment(),
                this.dhFramebuffer.getFramebuffer().getDepthAttachment());

        // Restore VulkanMod render state
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
    public void cleanup() {
        for (CachedBuffer cached : this.vulkanBufferCache.values()) {
            cached.free();
        }
        this.vulkanBufferCache.clear();

        if (this.quadIndexBuffer != null) {
            this.quadIndexBuffer.scheduleFree();
            this.quadIndexBuffer = null;
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
        LOGGER.info("[DH-Vulkan] VulkanRenderDelegate cleaned up.");
    }
}
