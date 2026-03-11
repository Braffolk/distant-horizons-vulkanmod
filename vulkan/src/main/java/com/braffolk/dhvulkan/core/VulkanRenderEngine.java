/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    VulkanMod rendering engine implementation.
 */

package com.braffolk.dhvulkan.core;

import com.braffolk.dhvulkan.core.data.RenderUniforms;
import com.braffolk.dhvulkan.core.data.VkVertexData;
import com.braffolk.dhvulkan.config.DhVulkanConfig;
import com.braffolk.dhvulkan.compat.Compat;
import com.braffolk.dhvulkan.core.pipeline.DhCompositePipeline;
import com.braffolk.dhvulkan.core.pipeline.DhDepthReaderPipeline;
import com.braffolk.dhvulkan.core.pipeline.DhFogPipeline;
import com.braffolk.dhvulkan.core.pipeline.DhSsaoPipeline;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.enums.EConfigEntryAppearance;
import com.seibel.distanthorizons.core.util.math.Mat4f;
import com.seibel.distanthorizons.core.util.math.Vec3f;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Core Vulkan rendering engine. Implements {@link VulkanBackend} using
 * VulkanMod's rendering API. DH-agnostic -- receives data through
 * {@link RenderUniforms} and {@link VkVertexData}.
 *
 * Refactored from VulkanRenderDelegate to separate DH integration
 * concerns from the actual Vulkan rendering logic.
 */
public class VulkanRenderEngine implements VulkanBackend {
    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanEngine");
    private static final Vec3f VEC3F_ZERO = new Vec3f(0, 0, 0);

    // Pre-allocated reusable objects to avoid per-frame heap allocations
    private final Mat4f tempCombinedMatrix = new Mat4f();
    private final Mat4f tempInvProj = new Mat4f();
    private final float[] tempInvProjArray = new float[16];
    private final float[] tempMcProjArray = new float[16];

    // VBO cache pruning: amortized sweep to detect dead VBOs
    private static final int PRUNE_BATCH_SIZE = 64;
    private int pruneIteratorIndex = 0;

    private final VulkanRenderContext renderContext;
    private boolean initialized = false;
    private boolean initFailed = false;

    // Frame state
    private boolean frameReady = false;
    private int drawCount = 0;

    /** DH-owned framebuffer -- LODs render into this instead of MC's render pass */
    private DhVulkanFramebuffer dhFramebuffer;
    /** Composite pipeline -- blends DH's framebuffer onto MC's */
    private DhCompositePipeline compositePipeline;
    /** SSAO pipeline */
    private DhSsaoPipeline ssaoPipeline;
    /** Fog pipeline */
    private DhFogPipeline fogPipeline;
    /** Depth reader -- copies MC depth to R32F for sampling on NVIDIA */
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
     * Cache of uploaded Vulkan vertex buffers, keyed by VkVertexData.id.
     */
    private final Map<Integer, CachedBuffer> vulkanBufferCache = new ConcurrentHashMap<>();

    private static class PendingFree {
        final int dataId;
        final CachedBuffer expectedEntry;

        PendingFree(int dataId, CachedBuffer expectedEntry) {
            this.dataId = dataId;
            this.expectedEntry = expectedEntry;
        }
    }

    /**
     * Thread-safe queue for VBOs pending GPU buffer free.
     */
    private final ConcurrentLinkedQueue<PendingFree> pendingFreeQueue = new ConcurrentLinkedQueue<>();

    /**
     * Batch from the PREVIOUS frame, ready to be freed this frame.
     */
    private java.util.List<PendingFree> pendingFreeBatch = new java.util.ArrayList<>();

    /** Saved VRenderSystem state -- restored in endFrame() */
    private boolean savedCullState;
    private boolean savedDepthMask;
    private int savedDepthFun;
    private int savedTopology;
    private int savedPolygonMode;
    private boolean savedBlendEnabled;
    private int savedBlendSrcRgb;
    private int savedBlendDstRgb;
    private int savedBlendSrcAlpha;
    private int savedBlendDstAlpha;
    private int savedBlendOp;

    public VulkanRenderEngine() {
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

        // Hot-reload config
        DhVulkanConfig.reload();

        if (this.initFailed)
            return;

        // Init (first frame only)
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
        VRenderSystem.topology = 3;   // VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
        VRenderSystem.polygonMode = 0; // VK_POLYGON_MODE_FILL
        PipelineState.blendInfo.enabled = false;

        // End MC's render pass, start DH's framebuffer pass
        Renderer.getInstance().endRenderPass();
        this.dhFramebuffer.beginRenderPass();

        // N+1 frame delay for GPU buffer frees
        for (PendingFree pf : this.pendingFreeBatch) {
            CachedBuffer current = this.vulkanBufferCache.get(pf.dataId);
            if (current == pf.expectedEntry) {
                this.vulkanBufferCache.remove(pf.dataId);
                current.free();
            }
        }
        this.pendingFreeBatch.clear();

        PendingFree pf;
        while ((pf = this.pendingFreeQueue.poll()) != null) {
            this.pendingFreeBatch.add(pf);
        }

        pruneDeadCacheEntries();

        // Bind terrain pipeline
        this.renderContext.bindTerrainPipeline();

        this.frameReady = true;
    }

    private void pruneDeadCacheEntries() {
        if (this.vulkanBufferCache.isEmpty())
            return;

        Integer[] keys = this.vulkanBufferCache.keySet().toArray(new Integer[0]);
        int total = keys.length;
        if (total == 0)
            return;

        if (this.pruneIteratorIndex >= total) {
            this.pruneIteratorIndex = 0;
        }

        int checked = 0;
        while (checked < PRUNE_BATCH_SIZE && checked < total) {
            int idx = (this.pruneIteratorIndex + checked) % total;
            int dataId = keys[idx];
            CachedBuffer cached = this.vulkanBufferCache.get(dataId);
            if (cached != null) {
                // Currently a no-op -- see pruning TODO in VulkanRenderDelegate
            }
            checked++;
        }
        this.pruneIteratorIndex += checked;
    }

    @Override
    public void fillUniforms(RenderUniforms uniforms) {
        if (!this.frameReady)
            return;

        // Use DH's projection matrix for terrain rendering
        this.tempCombinedMatrix.set(uniforms.dhProjectionMatrix);
        this.tempCombinedMatrix.multiply(uniforms.dhModelViewMatrix);
        this.renderContext.setUniformMat4("uCombinedMatrix", this.tempCombinedMatrix);
        this.renderContext.setUniformFloat("uWorldYOffset", (float) uniforms.worldYOffset);
        this.renderContext.setUniformFloat("uMircoOffset", 0.01f);

        float curveRatio = DhConfigHelper.earthCurveRatio();
        this.renderContext.setUniformFloat("uEarthRadius",
                (curveRatio < -1.0f || curveRatio > 1.0f) ? 6371000.0f / curveRatio : 0.0f);

        int renderDistChunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
        float overdrawConfig = DhConfigHelper.overdrawPrevention();
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
        this.renderContext.setUniformBool("uDitherDhRendering", DhConfigHelper.ditherDhFade());

        boolean noiseEnabled = DhConfigHelper.noiseEnabled();
        this.renderContext.setUniformBool("uNoiseEnabled", noiseEnabled);
        this.renderContext.setUniformInt("uNoiseSteps", DhConfigHelper.noiseSteps());
        this.renderContext.setUniformFloat("uNoiseIntensity", Compat.scaleNoiseIntensity(
                DhConfigHelper.noiseIntensity()));
        this.renderContext.setUniformInt("uNoiseDropoff", DhConfigHelper.noiseDropoff());
        this.renderContext.setUniformBool("uIsWhiteWorld", DhConfigHelper.whiteWorldEnabled());
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
    public void drawVertexData(VkVertexData data, int indexCount) {
        if (!this.frameReady || indexCount <= 0)
            return;

        int dataId = data.id;
        ByteBuffer handle = data.vertexBuffer;

        // VBO handle is null -- draw with stale cached buffer to prevent flicker
        if (handle == null) {
            CachedBuffer stale = this.vulkanBufferCache.get(dataId);
            if (stale != null) {
                try {
                    int quadCount = indexCount / 6;
                    if (quadCount > this.quadIndexBufferCapacity) {
                        this.ensureQuadIndexBuffer(quadCount + 1024);
                    }
                    this.renderContext.drawIndexed(stale.vkBuffer, this.quadIndexBuffer, indexCount);
                    this.drawCount++;
                } catch (Exception e) {
                    // Buffer may have been freed already
                }
            }
            return;
        }

        try {
            int handleId = data.handleIdentity;
            CachedBuffer cached = this.vulkanBufferCache.get(dataId);

            // Invalidate if handle changed (terrain re-uploaded)
            if (cached != null && cached.handleIdentity != handleId) {
                cached.free();
                this.vulkanBufferCache.remove(dataId);
                cached = null;
            }

            // Upload vertex data if not cached
            if (cached == null) {
                int dataSize = handle.remaining();
                if (dataSize <= 0)
                    return;

                Object vkBuffer = Compat.createGpuVertexBuffer(dataSize);
                handle.position(0);
                Compat.copyBuffer(vkBuffer, handle, dataSize);
                handle.position(0);

                cached = new CachedBuffer(vkBuffer, handleId);
                this.vulkanBufferCache.put(dataId, cached);
            }

            // Ensure index buffer capacity
            int quadCount = indexCount / 6;
            if (quadCount > this.quadIndexBufferCapacity) {
                this.ensureQuadIndexBuffer(quadCount + 1024);
            }

            // THE draw call
            this.renderContext.drawIndexed(cached.vkBuffer, this.quadIndexBuffer, indexCount);

        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] drawVertexData error", e);
        }
    }

    @Override
    public void queueDataFree(VkVertexData data) {
        int dataId = data.id;
        CachedBuffer cached = this.vulkanBufferCache.get(dataId);
        if (cached != null) {
            this.pendingFreeQueue.add(new PendingFree(dataId, cached));
        }
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
    public void endFrame(RenderUniforms uniforms) {
        if (!this.frameReady)
            return;

        try {
            // End DH's render pass
            Renderer.getInstance().endRenderPass();

            // SSAO post-process
            if (this.ssaoPipeline != null && DhConfigHelper.ssaoEnabled()) {
                try {
                    this.tempCombinedMatrix.set(uniforms.dhProjectionMatrix);
                    this.ssaoPipeline.render(this.dhFramebuffer, this.tempCombinedMatrix);
                } catch (Exception e) {
                    LOGGER.error("[DH-Vulkan] SSAO render failed", e);
                }
            }

            // Fog post-process
            if (this.fogPipeline != null && DhConfigHelper.dhFogEnabled()) {
                try {
                    this.tempCombinedMatrix.set(uniforms.dhModelViewMatrix);
                    this.tempInvProj.set(uniforms.dhProjectionMatrix);
                    this.fogPipeline.render(this.dhFramebuffer,
                            this.tempCombinedMatrix,
                            this.tempInvProj,
                            uniforms.partialTicks);
                } catch (Exception e) {
                    LOGGER.error("[DH-Vulkan] Fog render failed", e);
                }
            }

            // End any render pass left by SSAO/Fog, then rebind MC
            Renderer.getInstance().endRenderPass();
            Compat.rebindMainTarget();

            // Check fade mode
            com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode fadeMode = DhConfigHelper.vanillaFadeMode();
            if (fadeMode == com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode.NONE) {
                VulkanImage debugMcDepth = DhVulkanConfig.get().vulkanRenderMode == 6
                        ? Compat.getSwapChainDepthAttachment()
                        : null;
                this.runComposite(uniforms, debugMcDepth);
            } else if (DhVulkanConfig.get().vulkanRenderMode != 6) {
                this.runComposite(uniforms, null);
            }

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
    public void deferredComposite(RenderUniforms uniforms) {
        com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode fadeMode = DhConfigHelper.vanillaFadeMode();

        if (fadeMode == com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode.NONE) {
            return;
        }

        try {
            VulkanImage mcDepth = Compat.getSwapChainDepthAttachment();
            Renderer.getInstance().endRenderPass();

            VulkanImage mcDepthR32F = null;
            if (this.depthReaderPipeline != null) {
                mcDepthR32F = this.depthReaderPipeline.readDepth(mcDepth);
            }

            Compat.rebindMainTarget();
            this.runComposite(uniforms, mcDepthR32F);
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] deferredComposite error, falling back to no-depth", e);
            try {
                Renderer.getInstance().endRenderPass();
                Compat.rebindMainTarget();
                this.runComposite(uniforms, null);
            } catch (Exception e2) {
                LOGGER.error("[DH-Vulkan] Fallback composite also failed", e2);
            }
        }
    }

    private void runComposite(RenderUniforms uniforms, VulkanImage mcDepthTexture) {
        if (this.compositePipeline != null && this.dhFramebuffer != null) {
            int debugMode = DhVulkanConfig.get().vulkanRenderMode;
            VulkanImage ssaoTex = this.ssaoPipeline != null ? this.ssaoPipeline.getIntermediateTexture() : null;
            VulkanImage fogTex = this.fogPipeline != null ? this.fogPipeline.getIntermediateTexture() : null;

            // uInvProj = inverse of DH's projection
            this.tempInvProj.set(uniforms.dhProjectionMatrix);
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

            // uMcProj = MC's projection
            this.tempMcProjArray[0] = uniforms.mcProjectionMatrix.m00;
            this.tempMcProjArray[1] = uniforms.mcProjectionMatrix.m10;
            this.tempMcProjArray[2] = uniforms.mcProjectionMatrix.m20;
            this.tempMcProjArray[3] = uniforms.mcProjectionMatrix.m30;
            this.tempMcProjArray[4] = uniforms.mcProjectionMatrix.m01;
            this.tempMcProjArray[5] = uniforms.mcProjectionMatrix.m11;
            this.tempMcProjArray[6] = uniforms.mcProjectionMatrix.m21;
            this.tempMcProjArray[7] = uniforms.mcProjectionMatrix.m31;
            this.tempMcProjArray[8] = uniforms.mcProjectionMatrix.m02;
            this.tempMcProjArray[9] = uniforms.mcProjectionMatrix.m12;
            this.tempMcProjArray[10] = uniforms.mcProjectionMatrix.m22;
            this.tempMcProjArray[11] = uniforms.mcProjectionMatrix.m32;
            this.tempMcProjArray[12] = uniforms.mcProjectionMatrix.m03;
            this.tempMcProjArray[13] = uniforms.mcProjectionMatrix.m13;
            this.tempMcProjArray[14] = uniforms.mcProjectionMatrix.m23;
            this.tempMcProjArray[15] = uniforms.mcProjectionMatrix.m33;

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
        try {
            Compat.waitDeviceIdle();
        } catch (Exception e) {
            LOGGER.warn("[DH-Vulkan] waitDeviceIdle failed during cleanup", e);
        }

        this.pendingFreeBatch.clear();
        PendingFree pf;
        while ((pf = this.pendingFreeQueue.poll()) != null) {
            // Drained, freed in cache sweep below
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

        Compat.cleanupStaticResources();

        this.initialized = false;
        this.initFailed = false;
        LOGGER.info("[DH-Vulkan] VulkanRenderEngine cleaned up.");
    }

    /**
     * Lock or hide config settings that are unsupported on the Vulkan path.
     */
    private void disableUnsupportedSettings() {
        Config.Client.Advanced.Debugging.renderWireframe.setApiValue(false);
        Config.Client.Advanced.Debugging.DebugWireframe.enableRendering.setApiValue(false);
        Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue.setApiValue(false);
        Config.Client.Advanced.Debugging.DebugWireframe.showNetworkSyncOnLoadQueue.setApiValue(false);
        Config.Client.Advanced.Debugging.DebugWireframe.showRenderSectionStatus.setApiValue(false);
        Config.Client.Advanced.Debugging.DebugWireframe.showRenderSectionToggling.setApiValue(false);
        Config.Client.Advanced.Debugging.DebugWireframe.showQuadTreeRenderStatus.setApiValue(false);
        Config.Client.Advanced.Debugging.DebugWireframe.showFullDataUpdateStatus.setApiValue(false);

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
