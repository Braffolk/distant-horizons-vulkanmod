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
import java.util.HashMap;
import java.util.Map;
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
    private final float[] tempInvMcMvmProjArray = new float[16];



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
    /** Cached MC depth texture read at beginFrame() for use in deferredComposite() */
    private VulkanImage cachedMcDepthTexture = null;
    /** Cloud renderer -- renders clouds after DH composite with correct depth */
    private final VulkanCloudRenderer cloudRenderer = new VulkanCloudRenderer();

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
    private final Map<Integer, CachedBuffer> vulkanBufferCache = new HashMap<>();

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
        ByteBuffer indexData = org.lwjgl.system.MemoryUtil.memAlloc(indexCount * 4);
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
        org.lwjgl.system.MemoryUtil.memFree(indexData);
        this.quadIndexBufferCapacity = quadCount;
    }

    @Override
    public void beginFrame() {
        this.drawCount = 0;
        this.frameReady = false;

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


        // Bind terrain pipeline
        this.renderContext.bindTerrainPipeline();

        this.frameReady = true;
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
        int vpWidth = Minecraft.getInstance().getWindow().getWidth();
        int vpHeight = Minecraft.getInstance().getWindow().getHeight();
        this.renderContext.setUniformFloat("uClipDistance",
                DhConfigHelper.getShaderClipDistance(renderDistChunks, vpWidth, vpHeight));
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

                // Data is now on the GPU — release the CPU copy immediately.
                // This frees potentially hundreds of MB of native memory that
                // would otherwise sit idle until the wrapper is closed.
                data.clearData();

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
            // End DH's render pass — transitions attachments to SHADER_READ_ONLY
            Renderer.getInstance().endRenderPass();

            // SSAO post-process (between LOD render and composite)
            if (this.ssaoPipeline != null && DhConfigHelper.ssaoEnabled()) {
                try {
                    this.tempCombinedMatrix.set(uniforms.dhProjectionMatrix);
                    this.ssaoPipeline.render(this.dhFramebuffer, this.tempCombinedMatrix);
                } catch (Exception e) {
                    LOGGER.error("[DH-Vulkan] SSAO render failed", e);
                }
            }

            // Fog post-process (after SSAO, before composite)
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

            // Phase 1 composite: draw LODs WITHOUT MC depth comparison.
            // MC terrain hasn't rendered yet (DH fires at prepareChunkRenders @HEAD).
            // For debug mode 6: use cached depth from previous frame (acceptable for debug).
            // For normal rendering: null depth — Phase 2b at renderLevel @RETURN
            // will re-composite with real MC depth for SINGLE/DOUBLE modes.
            com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode fadeMode = DhConfigHelper.vanillaFadeMode();
            if (fadeMode == com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode.NONE) {
                // NONE: only composite, no Phase 2b re-composite.
                VulkanImage mcDepthForComposite = DhVulkanConfig.get().vulkanRenderMode == 6 && this.depthReaderPipeline != null
                        ? this.depthReaderPipeline.getCachedDepthTexture()
                        : null;
                this.runComposite(uniforms, mcDepthForComposite);
            } else if (DhVulkanConfig.get().vulkanRenderMode != 6) {
                // SINGLE/DOUBLE normal: draw LOD colors with depth=1.0 (shader handles it).
                // Phase 2b (lateComposite at renderLevel @RETURN) re-composites with
                // real MC depth AND proper gl_FragDepth writes.
                this.runComposite(uniforms, null);
            }
            // SINGLE/DOUBLE + mode 6: skip Phase 1 entirely —
            // lateComposite alone handles debug via depth reader (matches vm.5).

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
        // Fallback: called from addCloudsPass @HEAD if that hook fires.
        // On VulkanMod 0.6.1, addCloudsPass does NOT exist — all work is done
        // in lateComposite (renderLevel @RETURN) instead.
        // This is kept for compatibility with VM versions that DO call addCloudsPass.
        if (!this.frameReady) return;
        // If this fires, skip lateComposite's work by marking phase2 done.
        this.phase2Done = true;
        doPhase2(uniforms);
    }

    /**
     * Called from renderLevel @RETURN — AFTER terrain, weather, everything.
     * 
     * On VulkanMod 0.6.1, this is the ONLY hook that fires (addCloudsPass doesn't exist).
     * Does: read MC depth, re-composite LODs with depth comparison, render clouds.
     */
    @Override
    public void lateComposite(RenderUniforms uniforms) {
        if (this.phase2Done) {
            this.phase2Done = false;
            return; // Already handled by deferredComposite
        }

        com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode fadeMode = DhConfigHelper.vanillaFadeMode();
        if (fadeMode == com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode.NONE) {
            // NONE: Phase 1 was the only composite. Just render clouds.
            if (this.frameReady) {
                this.cloudRenderer.renderIfEnabled(uniforms.partialTicks, uniforms.mcProjectionMatrix, uniforms.dhModelViewMatrix);
            }
            return;
        }

        if (!this.frameReady) return;
        doPhase2(uniforms);
    }

    private boolean phase2Done = false;

    /**
     * Phase 2: read MC depth, re-composite LODs with depth comparison, render clouds.
     * Called from whichever hook fires first (deferredComposite or lateComposite).
     */
    private void doPhase2(RenderUniforms uniforms) {
        try {
            VulkanImage mcDepth = Compat.getSwapChainDepthAttachment();
            Renderer.getInstance().endRenderPass();

            // Read MC depth into sampleable R32F
            VulkanImage mcDepthR32F = null;
            if (this.depthReaderPipeline != null) {
                mcDepthR32F = this.depthReaderPipeline.readDepth(mcDepth);
                this.cachedMcDepthTexture = mcDepthR32F;
            }

            Compat.rebindMainTarget();
            this.runComposite(uniforms, mcDepthR32F);

            // Clouds render in the same render pass, depth-testing against combined depth.
            this.cloudRenderer.renderIfEnabled(uniforms.partialTicks, uniforms.mcProjectionMatrix, uniforms.dhModelViewMatrix);
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Phase 2 composite error", e);
            try {
                Renderer.getInstance().endRenderPass();
                Compat.rebindMainTarget();
            } catch (Exception e2) {
                LOGGER.error("[DH-Vulkan] Recovery also failed", e2);
            }
        }
    }

    @Override
    public void readAndCacheMcDepth() {
        if (!this.initialized || this.initFailed || this.depthReaderPipeline == null)
            return;

        com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode fadeMode = DhConfigHelper.vanillaFadeMode();
        if (fadeMode == com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode.NONE
                && DhVulkanConfig.get().vulkanRenderMode != 6) {
            return; // no need to read depth
        }

        try {
            VulkanImage mcDepth = Compat.getSwapChainDepthAttachment();
            Renderer.getInstance().endRenderPass();

            this.cachedMcDepthTexture = this.depthReaderPipeline.readDepth(mcDepth);

            Compat.rebindMainTarget();
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] readAndCacheMcDepth failed", e);
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

            // uInvMcMvmProj = inverse of MC's combined modelview-projection matrix
            // Used for reconstructing MC fragment world position for distance fade
            this.tempCombinedMatrix.set(uniforms.mcProjectionMatrix);
            this.tempCombinedMatrix.multiply(uniforms.dhModelViewMatrix);
            this.tempCombinedMatrix.invert();
            this.tempInvMcMvmProjArray[0] = tempCombinedMatrix.m00;
            this.tempInvMcMvmProjArray[1] = tempCombinedMatrix.m10;
            this.tempInvMcMvmProjArray[2] = tempCombinedMatrix.m20;
            this.tempInvMcMvmProjArray[3] = tempCombinedMatrix.m30;
            this.tempInvMcMvmProjArray[4] = tempCombinedMatrix.m01;
            this.tempInvMcMvmProjArray[5] = tempCombinedMatrix.m11;
            this.tempInvMcMvmProjArray[6] = tempCombinedMatrix.m21;
            this.tempInvMcMvmProjArray[7] = tempCombinedMatrix.m31;
            this.tempInvMcMvmProjArray[8] = tempCombinedMatrix.m02;
            this.tempInvMcMvmProjArray[9] = tempCombinedMatrix.m12;
            this.tempInvMcMvmProjArray[10] = tempCombinedMatrix.m22;
            this.tempInvMcMvmProjArray[11] = tempCombinedMatrix.m32;
            this.tempInvMcMvmProjArray[12] = tempCombinedMatrix.m03;
            this.tempInvMcMvmProjArray[13] = tempCombinedMatrix.m13;
            this.tempInvMcMvmProjArray[14] = tempCombinedMatrix.m23;
            this.tempInvMcMvmProjArray[15] = tempCombinedMatrix.m33;

            // Fade distances — matches DH base's VanillaFadeShader.onApplyUniforms():
            //   dhNearClip = getNearClipPlaneInBlocks() + 16
            //   fadeStart = dhNearClip * 1.5
            //   fadeEnd = dhNearClip * 1.9
            int renderDist = com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector.INSTANCE
                    .get(com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper.class)
                    .getRenderDistance();
            var renderWrapper = com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector.INSTANCE
                    .get(com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper.class);
            int vpW = renderWrapper.getTargetFramebufferViewportWidth();
            int vpH = renderWrapper.getTargetFramebufferViewportHeight();
            boolean lodOnly = Config.Client.Advanced.Debugging.lodOnlyMode.get();
            float dhNearClipDistance = DhConfigHelper.getNearClipPlaneInBlocks(renderDist, vpW, vpH, lodOnly) + 16f;
            float fadeStartDist = dhNearClipDistance * 1.5f;
            float fadeEndDist = dhNearClipDistance * 1.9f;

            this.compositePipeline.render(
                    this.dhFramebuffer.getFramebuffer().getColorAttachment(),
                    this.dhFramebuffer.getFramebuffer().getDepthAttachment(),
                    ssaoTex, fogTex,
                    mcDepthTexture,
                    debugMode, this.tempInvProjArray, this.tempMcProjArray,
                    this.tempInvMcMvmProjArray, fadeStartDist, fadeEndDist);
        }
    }

    @Override
    public void cleanup() {
        this.cloudRenderer.cleanup();

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
