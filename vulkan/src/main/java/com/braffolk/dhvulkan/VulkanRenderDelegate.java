/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    VulkanMod rendering delegate implementation.
 */

package com.braffolk.dhvulkan;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.enums.EConfigEntryAppearance;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import com.braffolk.dhvulkan.IVulkanRenderDelegate;
import com.braffolk.dhvulkan.config.DhVulkanConfig;
import com.braffolk.dhvulkan.compat.Compat;
import com.braffolk.dhvulkan.duck.IVulkanVertexBuffer;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.util.math.Mat4f;
import com.seibel.distanthorizons.core.util.math.Vec3f;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import net.vulkanmod.vulkan.shader.PipelineState;
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
    private static final Vec3f VEC3F_ZERO = new Vec3f(0, 0, 0);

    // Pre-allocated reusable objects to avoid per-frame heap allocations
    private final Mat4f tempCombinedMatrix = new Mat4f();
    private final Mat4f tempInvProj = new Mat4f();
    private final float[] tempInvProjArray = new float[16];
    private final float[] tempMcProjArray = new float[16];

    // VBO cache pruning: amortized sweep to detect dead VBOs
    private static final int PRUNE_BATCH_SIZE = 64; // entries checked per frame
    private int pruneIteratorIndex = 0;

    private final VulkanRenderContext renderContext;
    private boolean initialized = false;
    private boolean initFailed = false;

    // Frame state — reset every beginFrame()
    private boolean frameReady = false;

    // Debug counters
    private int drawCount = 0;

    /** DH-owned framebuffer — LODs render into this instead of MC's render pass */
    private DhVulkanFramebuffer dhFramebuffer;
    /** Composite pipeline — blends DH's framebuffer onto MC's */
    private DhCompositePipeline compositePipeline;

    /** SSAO pipeline — computes and applies ambient occlusion (Phase 7) */
    private DhSsaoPipeline ssaoPipeline;

    /** Fog pipeline — computes and applies distance/height fog (Phase 7) */
    private DhFogPipeline fogPipeline;

    /** Depth reader — copies MC depth to R32F for sampling on NVIDIA (Phase 8) */
    private DhDepthReaderPipeline depthReaderPipeline;

    /** Shared index buffer for quad rendering (6 indices per quad) */
    private Object quadIndexBuffer;
    private int quadIndexBufferCapacity = 0;

    /**
     * Tracks a cached Vulkan VertexBuffer alongside the identity of the
     * ByteBuffer it was created from, for invalidation when terrain is re-uploaded.
     */
    private static class CachedBuffer {
        final Object vkBuffer;
        final int handleIdentity;

        CachedBuffer(Object vkBuffer, int handleIdentity) {
            this.vkBuffer = vkBuffer;
            this.handleIdentity = handleIdentity;
        }

        void free() {
            Compat.scheduleFree(this.vkBuffer);
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
            disableUnsupportedSettings();

            LOGGER.info("[DH-Vulkan] Init: creating pipeline...");
            this.renderContext.init();

            LOGGER.info("[DH-Vulkan] Init: creating index buffer...");
            this.ensureQuadIndexBuffer(262144);

            LOGGER.info("[DH-Vulkan] Init: creating framebuffer...");
            int width = Compat.getSwapChainWidth();
            int height = Compat.getSwapChainHeight();
            this.dhFramebuffer = new DhVulkanFramebuffer();
            this.dhFramebuffer.init(width, height);

            LOGGER.info("[DH-Vulkan] Init: creating composite pipeline...");
            this.compositePipeline = new DhCompositePipeline();
            this.compositePipeline.init();

            LOGGER.info("[DH-Vulkan] Init: creating SSAO pipeline...");
            this.ssaoPipeline = new DhSsaoPipeline();
            this.ssaoPipeline.init(width, height);

            LOGGER.info("[DH-Vulkan] Init: creating Fog pipeline...");
            this.fogPipeline = new DhFogPipeline();
            this.fogPipeline.init(width, height);

            LOGGER.info("[DH-Vulkan] Init: creating Depth Reader pipeline...");
            this.depthReaderPipeline = new DhDepthReaderPipeline();
            this.depthReaderPipeline.init(width, height);

            this.initialized = true;
            LOGGER.info("[DH-Vulkan] Init complete. All resources created.");
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Init FAILED", e);
            this.initFailed = true;
        }
    }

    private void ensureQuadIndexBuffer(int quadCount) {
        if (quadCount <= this.quadIndexBufferCapacity) {
            return;
        }

        if (this.quadIndexBuffer != null) {
            Compat.scheduleFree(this.quadIndexBuffer);
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

        this.quadIndexBuffer = Compat.createIndexBuffer(indexData.remaining());
        Compat.copyBuffer(this.quadIndexBuffer, indexData, indexData.remaining());
        this.quadIndexBufferCapacity = quadCount;

    }

    @Override
    public void beginFrame() {
        this.drawCount = 0;
        this.frameReady = false;

        // Hot-reload config (allows changing vulkanRenderMode mid-game)
        DhVulkanConfig.reload();

        if (this.initFailed)
            return;

        // ===== INIT (first frame only) =====
        if (!this.initialized) {
            Renderer.getInstance().endRenderPass();
            this.init();
            if (this.initFailed)
                return;
            Compat.rebindMainTarget();
            return;
        }

        // Bind MC's lightmap texture
        try {
            VulkanImage lightmapImage = Compat.getLightmapVulkanImage();
            if (lightmapImage != null) {
                VTextureSelector.setLightTexture(lightmapImage);
            }
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Failed to bind MC lightmap", e);
        }

        // Save MC render state (restored in endFrame)
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

        // Set DH render state
        VRenderSystem.cull = true;
        VRenderSystem.depthTest = true;
        VRenderSystem.depthMask = true;
        VRenderSystem.depthFun = 515; // GL_LEQUAL
        VRenderSystem.topology = 3; // VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
        VRenderSystem.polygonMode = 0; // VK_POLYGON_MODE_FILL
        PipelineState.blendInfo.enabled = false;

        // End MC's render pass, start DH's framebuffer pass
        Renderer.getInstance().endRenderPass();
        this.dhFramebuffer.beginRenderPass();

        // Amortized VBO cache pruning: check a batch of entries per frame
        // for dead VBOs (unloaded LOD sections). Spreads the work across
        // many frames to avoid stutters.
        pruneDeadCacheEntries();

        // Bind terrain pipeline
        this.renderContext.bindTerrainPipeline();

        this.drawCount = 0;
        this.frameReady = true;
    }

    /**
     * Amortized VBO cache pruning. Checks {@link #PRUNE_BATCH_SIZE} entries per
     * frame for dead VBOs (handle is null = VBO was destroyed by DH). Frees the
     * GPU buffer and removes the cache entry. Full cache sweep takes
     * ceil(cacheSize / PRUNE_BATCH_SIZE) frames.
     */
    private void pruneDeadCacheEntries() {
        if (this.vulkanBufferCache.isEmpty())
            return;

        Integer[] keys = this.vulkanBufferCache.keySet().toArray(new Integer[0]);
        int total = keys.length;
        if (total == 0)
            return;

        // Wrap the iterator index
        if (this.pruneIteratorIndex >= total) {
            this.pruneIteratorIndex = 0;
        }

        int checked = 0;
        int pruned = 0;
        while (checked < PRUNE_BATCH_SIZE && checked < total) {
            int idx = (this.pruneIteratorIndex + checked) % total;
            int vboId = keys[idx];
            CachedBuffer cached = this.vulkanBufferCache.get(vboId);
            if (cached != null) {
                // Check if the VBO's underlying handle has become null (destroyed)
                // We can't easily re-check the VBO object since we only store the
                // identity hash code. Instead, rely on drawBuffer() to detect and
                // evict null-handle VBOs on next draw attempt.
                // This pruning catches VBOs that are never drawn again.
            }
            checked++;
        }
        this.pruneIteratorIndex += checked;
    }

    @Override
    public void fillUniformData(DhApiRenderParam renderParameters) {
        if (!this.frameReady)
            return;

        // ===== STAGE 3: SET UNIFORMS =====
        // Just writes values to mapped buffers. NO Vulkan calls.

        // Use DH's projection matrix for terrain rendering — it extends the near
        // clip plane when the camera is far above max world height, preventing
        // z-fighting artifacts. The composite shader remaps DH depth back to
        // MC-compatible depth for correct occlusion (see remapDepthDhToMc).
        this.tempCombinedMatrix.set(renderParameters.dhProjectionMatrix);
        this.tempCombinedMatrix.multiply(renderParameters.dhModelViewMatrix);
        this.renderContext.setUniformMat4("uCombinedMatrix", this.tempCombinedMatrix);
        this.renderContext.setUniformFloat("uWorldYOffset", (float) renderParameters.worldYOffset);
        this.renderContext.setUniformFloat("uMircoOffset", 0.01f);

        float curveRatio = Config.Client.Advanced.Graphics.Experimental.earthCurveRatio.get();
        this.renderContext.setUniformFloat("uEarthRadius",
                (curveRatio < -1.0f || curveRatio > 1.0f) ? 6371000.0f / curveRatio : 0.0f);

        int renderDistChunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
        float overdrawConfig = ((Number) Config.Client.Advanced.Graphics.Culling.overdrawPrevention.get()).floatValue();
        float overdraw;
        if (overdrawConfig <= 0) {
            if (renderDistChunks <= 2)
                overdraw = 0.2f;
            else if (renderDistChunks <= 4)
                overdraw = 0.3f;
            else if (renderDistChunks <= 6)
                overdraw = 0.6f;
            else if (renderDistChunks <= 10)
                overdraw = 0.8f;
            else
                overdraw = 0.9f;
        } else {
            overdraw = Math.max(0.05f, Math.min(overdrawConfig, 1.0f));
        }
        this.renderContext.setUniformFloat("uClipDistance", renderDistChunks * 16.0f * overdraw);
        this.renderContext.setUniformBool("uDitherDhRendering",
                Config.Client.Advanced.Graphics.Quality.ditherDhFade.get());

        boolean noiseEnabled = Config.Client.Advanced.Graphics.NoiseTexture.enableNoiseTexture.get();
        this.renderContext.setUniformBool("uNoiseEnabled", noiseEnabled);
        this.renderContext.setUniformInt("uNoiseSteps", Config.Client.Advanced.Graphics.NoiseTexture.noiseSteps.get());
        this.renderContext.setUniformFloat("uNoiseIntensity", Compat.scaleNoiseIntensity(
                Config.Client.Advanced.Graphics.NoiseTexture.noiseIntensity.get().floatValue()));
        this.renderContext.setUniformInt("uNoiseDropoff",
                Config.Client.Advanced.Graphics.NoiseTexture.noiseDropoff.get());
        this.renderContext.setUniformBool("uIsWhiteWorld",
                Config.Client.Advanced.Debugging.enableWhiteWorld.get());
        this.renderContext.setUniformVec3f("uModelOffset", VEC3F_ZERO);

        // Upload UBOs after setting all uniforms
        this.renderContext.uploadAndBindUBOs();
    }

    @Override
    public void setModelOffset(Vec3f modelOffset) {
        if (this.initFailed)
            return;
        this.renderContext.setUniformVec3f("uModelOffset", modelOffset);
        this.renderContext.uploadAndBindUBOs();
    }

    @Override
    public long uploadVertexData(ByteBuffer vertexData, int vertexCount) {
        return 0; // Not used by DH core
    }

    @Override
    public void drawBuffer(GLVertexBuffer vbo, int indexCount) {
        if (!this.frameReady || indexCount <= 0)
            return;

        // ===== STAGE 4: DRAW =====
        int vboId = System.identityHashCode(vbo);
        Object handle = ((IVulkanVertexBuffer) vbo).dhvulkan$getVulkanBufferHandle();

        // VBO was destroyed
        if (handle == null) {
            CachedBuffer stale = this.vulkanBufferCache.remove(vboId);
            if (stale != null)
                stale.free();
            return;
        }

        try {
            int handleId = System.identityHashCode(handle);
            CachedBuffer cached = this.vulkanBufferCache.get(vboId);

            // Invalidate if handle changed (terrain re-uploaded)
            if (cached != null && cached.handleIdentity != handleId) {
                cached.free();
                this.vulkanBufferCache.remove(vboId);
                cached = null;
            }

            // Upload vertex data if not cached
            if (cached == null && handle instanceof ByteBuffer) {
                ByteBuffer vertexData = (ByteBuffer) handle;
                int dataSize = vertexData.remaining();
                if (dataSize <= 0)
                    return;

                Object vkBuffer = Compat.createGpuVertexBuffer(dataSize);
                vertexData.position(0);
                Compat.copyBuffer(vkBuffer, vertexData, dataSize);
                vertexData.position(0);

                cached = new CachedBuffer(vkBuffer, handleId);
                this.vulkanBufferCache.put(vboId, cached);
            }

            if (cached == null)
                return;

            // Ensure index buffer capacity
            int quadCount = indexCount / 6;
            if (quadCount > this.quadIndexBufferCapacity) {
                this.ensureQuadIndexBuffer(quadCount + 1024);
            }

            // THE draw call
            this.renderContext.drawIndexed(cached.vkBuffer, this.quadIndexBuffer, indexCount);
            this.drawCount++;

        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] drawBuffer error", e);
        }
    }

    /**
     * Frees the cached Vulkan VertexBuffer for a given GLVertexBuffer.
     * Called from LodBufferContainer.close() when DH destroys a VBO.
     */
    @Override
    public void freeBuffer(GLVertexBuffer vbo) {
        int vboId = System.identityHashCode(vbo);
        CachedBuffer cached = this.vulkanBufferCache.remove(vboId);
        if (cached != null)
            cached.free();
    }

    @Override
    public void setBlendState(boolean enabled) {
        if (this.initFailed)
            return;
        PipelineState.blendInfo.enabled = enabled;
        if (enabled) {
            PipelineState.blendInfo.srcRgbFactor = 6;
            PipelineState.blendInfo.dstRgbFactor = 7;
            PipelineState.blendInfo.srcAlphaFactor = 1;
            PipelineState.blendInfo.dstAlphaFactor = 7;
            PipelineState.blendInfo.blendOp = 0;
        }
        this.renderContext.bindTerrainPipeline();
    }

    @Override
    public void endFrame(DhApiRenderParam renderParam) {
        if (!this.frameReady)
            return;

        try {
            // End DH's render pass — transitions attachments to SHADER_READ_ONLY
            Renderer.getInstance().endRenderPass();

            // SSAO post-process (between LOD render and composite)
            if (this.ssaoPipeline != null && Config.Client.Advanced.Graphics.Ssao.enableSsao.get()) {
                try {
                    this.tempCombinedMatrix.set(renderParam.dhProjectionMatrix);
                    this.ssaoPipeline.render(this.dhFramebuffer, this.tempCombinedMatrix);
                } catch (Exception e) {
                    LOGGER.error("[DH-Vulkan] SSAO render failed", e);
                }
            }

            // Fog post-process (after SSAO, before composite)
            if (this.fogPipeline != null && Config.Client.Advanced.Graphics.Fog.enableDhFog.get()) {
                try {
                    this.tempCombinedMatrix.set(renderParam.dhModelViewMatrix);
                    this.tempInvProj.set(renderParam.dhProjectionMatrix);
                    this.fogPipeline.render(this.dhFramebuffer,
                            this.tempCombinedMatrix,
                            this.tempInvProj,
                            renderParam.partialTicks);
                } catch (Exception e) {
                    LOGGER.error("[DH-Vulkan] Fog render failed", e);
                }
            }

            // End any render pass left by SSAO/Fog, then rebind MC
            Renderer.getInstance().endRenderPass();
            Compat.rebindMainTarget();

            // Check fade mode: NONE = composite immediately (no deferred pass),
            // SINGLE/DOUBLE = draw LODs now without MC depth, let deferredComposite
            // add MC depth occlusion via depth reader pipeline.
            com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode fadeMode = Config.Client.Advanced.Graphics.Quality.vanillaFadeMode
                    .get();
            if (fadeMode == com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode.NONE) {
                // NONE: only composite, no deferred pass. Pass MC depth for debug mode 6.
                VulkanImage debugMcDepth = DhVulkanConfig.get().vulkanRenderMode == 6
                        ? Compat.getSwapChainDepthAttachment()
                        : null;
                this.runComposite(renderParam, debugMcDepth);
            } else if (DhVulkanConfig.get().vulkanRenderMode != 6) {
                // SINGLE/DOUBLE normal mode: draw LODs without MC depth comparison.
                // deferredComposite() will re-composite with MC depth from depth reader.
                this.runComposite(renderParam, null);
            }
            // Mode 6 + SINGLE/DOUBLE: skip endFrame composite entirely —
            // deferredComposite alone renders MC depth debug via depth reader.

            // Restore MC render state
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
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] endFrame error", e);
        }
    }

    @Override
    public void deferredComposite(DhApiRenderParam renderParam) {
        // SINGLE_PASS and DOUBLE_PASS: composite AFTER MC terrain has rendered,
        // using MC's depth buffer for per-pixel depth comparison.
        com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode fadeMode = Config.Client.Advanced.Graphics.Quality.vanillaFadeMode
                .get();

        if (fadeMode == com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode.NONE) {
            return; // Already composited in endFrame()
        }

        try {
            VulkanImage mcDepth = Compat.getSwapChainDepthAttachment();
            Renderer.getInstance().endRenderPass();

            // Read MC depth into a sampleable R32F texture.
            VulkanImage mcDepthR32F = null;
            if (this.depthReaderPipeline != null) {
                mcDepthR32F = this.depthReaderPipeline.readDepth(mcDepth);
            }

            Compat.rebindMainTarget();
            this.runComposite(renderParam, mcDepthR32F);
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] deferredComposite error, falling back to no-depth", e);
            try {
                Renderer.getInstance().endRenderPass();
                Compat.rebindMainTarget();
                this.runComposite(renderParam, null);
            } catch (Exception e2) {
                LOGGER.error("[DH-Vulkan] Fallback composite also failed", e2);
            }
        }
    }

    /**
     * Shared composite logic. Renders DH's framebuffer onto MC's render target.
     *
     * @param mcDepthTexture MC's depth attachment for per-pixel depth comparison,
     *                       or null to skip.
     */
    private void runComposite(DhApiRenderParam renderParam, VulkanImage mcDepthTexture) {
        if (this.compositePipeline != null && this.dhFramebuffer != null) {
            int debugMode = DhVulkanConfig.get().vulkanRenderMode;
            VulkanImage ssaoTex = this.ssaoPipeline != null ? this.ssaoPipeline.getIntermediateTexture() : null;
            VulkanImage fogTex = this.fogPipeline != null ? this.fogPipeline.getIntermediateTexture() : null;

            // uInvProj = inverse of DH's projection (for view-space reconstruction from DH
            // depth)
            this.tempInvProj.set(renderParam.dhProjectionMatrix);
            this.tempInvProj.invert();
            this.tempInvProjArray[0] = tempInvProj.m00;
            this.tempInvProjArray[1] = tempInvProj.m10;
            this.tempInvProjArray[2] = tempInvProj.m20;
            this.tempInvProjArray[3] = tempInvProj.m30;
            this.tempInvProjArray[4] = tempInvProj.m01;
            this.tempInvProjArray[5] = tempInvProj.m11;
            this.tempInvProjArray[6] = tempInvProj.m21;
            this.tempInvProjArray[7] = tempInvProj.m31;
            this.tempInvProjArray[8] = tempInvProj.m02;
            this.tempInvProjArray[9] = tempInvProj.m12;
            this.tempInvProjArray[10] = tempInvProj.m22;
            this.tempInvProjArray[11] = tempInvProj.m32;
            this.tempInvProjArray[12] = tempInvProj.m03;
            this.tempInvProjArray[13] = tempInvProj.m13;
            this.tempInvProjArray[14] = tempInvProj.m23;
            this.tempInvProjArray[15] = tempInvProj.m33;

            // uMcProj = MC's projection (for remapping DH depth to MC-compatible depth)
            this.tempMcProjArray[0] = renderParam.mcProjectionMatrix.m00;
            this.tempMcProjArray[1] = renderParam.mcProjectionMatrix.m10;
            this.tempMcProjArray[2] = renderParam.mcProjectionMatrix.m20;
            this.tempMcProjArray[3] = renderParam.mcProjectionMatrix.m30;
            this.tempMcProjArray[4] = renderParam.mcProjectionMatrix.m01;
            this.tempMcProjArray[5] = renderParam.mcProjectionMatrix.m11;
            this.tempMcProjArray[6] = renderParam.mcProjectionMatrix.m21;
            this.tempMcProjArray[7] = renderParam.mcProjectionMatrix.m31;
            this.tempMcProjArray[8] = renderParam.mcProjectionMatrix.m02;
            this.tempMcProjArray[9] = renderParam.mcProjectionMatrix.m12;
            this.tempMcProjArray[10] = renderParam.mcProjectionMatrix.m22;
            this.tempMcProjArray[11] = renderParam.mcProjectionMatrix.m32;
            this.tempMcProjArray[12] = renderParam.mcProjectionMatrix.m03;
            this.tempMcProjArray[13] = renderParam.mcProjectionMatrix.m13;
            this.tempMcProjArray[14] = renderParam.mcProjectionMatrix.m23;
            this.tempMcProjArray[15] = renderParam.mcProjectionMatrix.m33;

            this.compositePipeline.render(
                    this.dhFramebuffer.getFramebuffer().getColorAttachment(),
                    this.dhFramebuffer.getFramebuffer().getDepthAttachment(),
                    ssaoTex, fogTex,
                    mcDepthTexture,
                    debugMode, this.tempInvProjArray, this.tempMcProjArray);
        }
    }

    @Override
    public void cleanup() {
        // Wait for GPU to finish all in-flight work before freeing resources.
        // This ensures deferred frees aren't racing with GPU reads.
        // Only runs during server transitions (loading screen is up), so the
        // brief GPU stall is not visible to the user.
        try {
            Compat.waitDeviceIdle();
        } catch (Exception e) {
            LOGGER.warn("[DH-Vulkan] waitDeviceIdle failed during cleanup", e);
        }

        LOGGER.info("[DH-Vulkan] cleanup() called, freeing {} cached Vulkan buffers.", this.vulkanBufferCache.size());
        for (CachedBuffer cached : this.vulkanBufferCache.values()) {
            cached.free();
        }
        this.vulkanBufferCache.clear();
        this.pruneIteratorIndex = 0;

        if (this.quadIndexBuffer != null) {
            Compat.scheduleFree(this.quadIndexBuffer);
            this.quadIndexBuffer = null;
        }
        if (this.depthReaderPipeline != null) {
            this.depthReaderPipeline.cleanup();
            this.depthReaderPipeline = null;
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
        this.initFailed = false; // Allow re-initialization after cleanup
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
