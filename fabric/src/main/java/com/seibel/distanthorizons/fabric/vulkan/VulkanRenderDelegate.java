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
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.VertexBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    private int frameCount = 0;

    /** Shared index buffer for quad rendering (6 indices per quad) */
    private IndexBuffer quadIndexBuffer;
    private int quadIndexBufferCapacity = 0;

    /**
     * Cache of uploaded Vulkan vertex buffers, keyed by the GLVertexBuffer's
     * vulkanBufferHandle identity hash.
     */
    private final Map<Integer, VertexBuffer> vulkanBufferCache = new ConcurrentHashMap<>();

    /** Saved VRenderSystem state — restored in endFrame() */
    private boolean savedCullState;
    private boolean savedDepthMask;
    private boolean savedBlendEnabled;

    public VulkanRenderDelegate() {
        this.renderContext = VulkanRenderContext.getInstance();
    }

    @Override
    public void init() {
        if (this.initialized || this.initFailed) {
            return;
        }

        LOGGER.info("[DH-Vulkan] VulkanRenderDelegate initializing...");
        try {
            this.renderContext.init();
            this.ensureQuadIndexBuffer(65536);
            this.initialized = true;
            LOGGER.info("[DH-Vulkan] VulkanRenderDelegate initialized successfully.");
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

        LOGGER.info("[DH-Vulkan] Quad IBO created/resized: {} quads, {} indices", quadCount, indexCount);
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
        this.savedBlendEnabled = PipelineState.blendInfo.enabled;
        VRenderSystem.cull = false; // DH handles its own face culling
        VRenderSystem.depthMask = true; // LODs need to write depth
        PipelineState.blendInfo.enabled = false; // Opaque LODs don't need blending

        // Push LOD depth slightly behind MC terrain so MC always wins depth test
        VRenderSystem.polygonOffset(8.0f, 256.0f);
        VRenderSystem.enablePolygonOffset();

        if (this.frameCount++ < 3) {
            LOGGER.info(
                    "[DH-Vulkan] beginFrame: savedCull={} cull={} depthTest={} depthMask={} depthFun={} topology={}",
                    this.savedCullState, VRenderSystem.cull, VRenderSystem.depthTest,
                    VRenderSystem.depthMask, VRenderSystem.depthFun, VRenderSystem.topology);
        }

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
        float dhNearClipDistance = RenderUtil.getNearClipPlaneInBlocks();
        if (!Config.Client.Advanced.Debugging.lodOnlyMode.get()) {
            dhNearClipDistance += 16f;
        }
        this.renderContext.setUniformFloat("uClipDistance", dhNearClipDistance);

        if (this.frameCount < 5) {
            LOGGER.info("[DH-Vulkan] uClipDistance={}", dhNearClipDistance);
        }

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
        int dataSize = vertexData.remaining();
        VertexBuffer vkVertexBuffer = new VertexBuffer(dataSize, MemoryTypes.HOST_MEM);
        vkVertexBuffer.copyBuffer(vertexData, dataSize);
        return vkVertexBuffer.getId();
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
            VertexBuffer vkBuffer;
            int handleId = System.identityHashCode(handle);

            vkBuffer = this.vulkanBufferCache.get(handleId);

            if (vkBuffer == null && handle instanceof ByteBuffer) {
                ByteBuffer vertexData = (ByteBuffer) handle;
                int dataSize = vertexData.remaining();

                if (dataSize <= 0) {
                    return;
                }

                vkBuffer = new VertexBuffer(dataSize, MemoryTypes.HOST_MEM);
                vertexData.position(0);
                vkBuffer.copyBuffer(vertexData, dataSize);
                vertexData.position(0);

                this.vulkanBufferCache.put(handleId, vkBuffer);
            }

            if (vkBuffer == null) {
                return;
            }

            // Ensure index buffer is large enough
            int quadCount = indexCount / 6;
            if (quadCount > this.quadIndexBufferCapacity) {
                this.ensureQuadIndexBuffer(quadCount + 1024);
            }

            // THE draw call
            this.renderContext.drawIndexed(vkBuffer, this.quadIndexBuffer, indexCount);

        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Error during drawBuffer: {}", e.getMessage());
        }
    }

    @Override
    public void endFrame() {
        // Restore VulkanMod render state
        VRenderSystem.cull = this.savedCullState;
        VRenderSystem.depthMask = this.savedDepthMask;
        PipelineState.blendInfo.enabled = this.savedBlendEnabled;
        VRenderSystem.disablePolygonOffset();
    }

    @Override
    public void cleanup() {
        for (VertexBuffer vb : this.vulkanBufferCache.values()) {
            vb.scheduleFree();
        }
        this.vulkanBufferCache.clear();

        if (this.quadIndexBuffer != null) {
            this.quadIndexBuffer.scheduleFree();
            this.quadIndexBuffer = null;
        }
        this.renderContext.cleanup();
        LOGGER.info("[DH-Vulkan] VulkanRenderDelegate cleaned up.");
    }
}
