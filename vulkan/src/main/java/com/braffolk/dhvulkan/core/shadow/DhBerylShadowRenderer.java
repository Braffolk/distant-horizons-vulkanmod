package com.braffolk.dhvulkan.core.shadow;

import com.braffolk.dhvulkan.compat.Compat;
import com.braffolk.dhvulkan.config.DhVulkanConfig;
import com.braffolk.dhvulkan.core.pipeline.DhShadowPipeline;
import net.vulkanmod.vulkan.VRenderSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Static helper that draws DH LODs into Beryl's shadow map.
 * <p>
 * Called from the Beryl mixin after {@code ShadowMap.renderShadowMap()} returns,
 * while the shadow render pass is still active. Uses the draw list recorded
 * during the PREVIOUS frame (one frame latency — standard for shadow maps).
 * <p>
 * The pipeline is depth-only; vertices are transformed by Beryl's light-space
 * matrix to generate correct shadow geometry for LOD terrain.
 */
public class DhBerylShadowRenderer {
    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanShadow");

    // ---- Shadow pipeline ----
    private static DhShadowPipeline shadowPipeline;
    private static boolean initAttempted = false;

    // ---- Draw batch recording ----
    /**
     * A recorded draw call from the previous frame.
     */
    public static class ShadowDrawBatch {
        public final Object vkBuffer;       // Vulkan vertex buffer
        public final Object indexBuffer;    // Shared quad index buffer
        public final int indexCount;
        public final float offsetX, offsetY, offsetZ;

        public ShadowDrawBatch(Object vkBuffer, Object indexBuffer, int indexCount,
                               float offsetX, float offsetY, float offsetZ) {
            this.vkBuffer = vkBuffer;
            this.indexBuffer = indexBuffer;
            this.indexCount = indexCount;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
        }
    }

    /** Draw list being recorded during the current frame. */
    private static List<ShadowDrawBatch> recordingBatches = new ArrayList<>();
    /** Draw list from the previous frame, used for shadow rendering. */
    private static List<ShadowDrawBatch> shadowBatches = new ArrayList<>();
    /** Current model offset being set by VulkanRenderEngine. */
    private static float currentOffsetX, currentOffsetY, currentOffsetZ;

    // ---- Recording API (called from VulkanRenderEngine) ----

    /**
     * Call at the start of each DH render frame to begin recording draw batches.
     */
    public static void beginRecording() {
        recordingBatches.clear();
    }

    /**
     * Record the current model offset as used by setModelOffset().
     */
    public static void recordModelOffset(float x, float y, float z) {
        currentOffsetX = x;
        currentOffsetY = y;
        currentOffsetZ = z;
    }

    /**
     * Record a draw call for shadow replay.
     */
    public static void recordDraw(Object vkBuffer, Object indexBuffer, int indexCount) {
        recordingBatches.add(new ShadowDrawBatch(
                vkBuffer, indexBuffer, indexCount,
                currentOffsetX, currentOffsetY, currentOffsetZ));
    }

    /**
     * Finalize recording — swap current into shadow list for next frame's shadow pass.
     */
    public static void endRecording() {
        // Swap: this frame's recordings become next frame's shadow source
        List<ShadowDrawBatch> temp = shadowBatches;
        shadowBatches = recordingBatches;
        recordingBatches = temp;
    }

    // ---- Shadow rendering (called from Beryl mixin) ----

    /**
     * Render DH LODs into Beryl's active shadow render pass.
     * <p>
     * MUST be called while Beryl's shadow framebuffer render pass is active.
     * Uses the previous frame's draw list.
     */
    public static void renderLodShadows() {
        if (!Compat.isBerylActive()) return;
        if (!DhVulkanConfig.get().berylShadowsEnabled) return;
        if (shadowBatches.isEmpty()) return;

        // Lazy init
        if (!initAttempted) {
            initAttempted = true;
            try {
                shadowPipeline = new DhShadowPipeline();
                shadowPipeline.init();
                LOGGER.info("[DH-Vulkan] Shadow pipeline initialized for Beryl integration.");
            } catch (Exception e) {
                LOGGER.error("[DH-Vulkan] Failed to initialize shadow pipeline", e);
                shadowPipeline = null;
                return;
            }
        }

        if (shadowPipeline == null || !shadowPipeline.isInitialized()) return;

        try {
            // Read Beryl's light-space matrix via reflection
            ByteBuffer lightSpaceMatrix = readBerylLightSpaceMatrix();
            if (lightSpaceMatrix == null) return;

            shadowPipeline.setLightSpaceMatrix(lightSpaceMatrix);

            // Save and set render state for depth-only shadow rendering
            boolean prevCull = VRenderSystem.cull;
            boolean prevDepthTest = VRenderSystem.depthTest;
            boolean prevDepthMask = VRenderSystem.depthMask;
            int prevDepthFun = VRenderSystem.depthFun;

            VRenderSystem.enableCull();
            VRenderSystem.enableDepthTest();
            VRenderSystem.depthMask = true;
            VRenderSystem.depthFunc(515); // GL_LEQUAL

            // Bind shadow pipeline
            shadowPipeline.bind();

            // Draw all recorded LOD batches
            for (ShadowDrawBatch batch : shadowBatches) {
                shadowPipeline.setModelOffset(batch.offsetX, batch.offsetY, batch.offsetZ);
                shadowPipeline.uploadUBOs();
                Compat.drawIndexed(batch.vkBuffer, batch.indexBuffer, batch.indexCount);
            }

            // Restore state
            VRenderSystem.cull = prevCull;
            VRenderSystem.depthTest = prevDepthTest;
            VRenderSystem.depthMask = prevDepthMask;
            VRenderSystem.depthFun = prevDepthFun;

        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Shadow rendering failed", e);
        }
    }

    // ---- Beryl reflection ----

    private static java.lang.reflect.Field lightSpaceMatrixField;
    private static boolean reflectionFailed = false;

    /**
     * Read RenderingPipeline.lightSpaceMatrix via reflection.
     * This is a MappedBuffer whose .buffer is a java.nio.ByteBuffer containing 16 floats.
     */
    private static ByteBuffer readBerylLightSpaceMatrix() {
        if (reflectionFailed) return null;

        try {
            if (lightSpaceMatrixField == null) {
                Class<?> pipelineClass = Class.forName("net.beryl.render.RenderingPipeline");
                lightSpaceMatrixField = pipelineClass.getDeclaredField("lightSpaceMatrix");
                lightSpaceMatrixField.setAccessible(true);
            }

            // lightSpaceMatrix is a MappedBuffer — we need its .buffer field
            Object mappedBuffer = lightSpaceMatrixField.get(null);
            if (mappedBuffer == null) return null;

            java.lang.reflect.Field bufferField = mappedBuffer.getClass().getField("buffer");
            bufferField.setAccessible(true);
            return (ByteBuffer) bufferField.get(mappedBuffer);

        } catch (Exception e) {
            LOGGER.warn("[DH-Vulkan] Failed to read Beryl lightSpaceMatrix: {}", e.getMessage());
            reflectionFailed = true;
            return null;
        }
    }

    public static void cleanup() {
        if (shadowPipeline != null) {
            shadowPipeline.cleanup();
            shadowPipeline = null;
        }
        initAttempted = false;
        reflectionFailed = false;
        lightSpaceMatrixField = null;
        recordingBatches.clear();
        shadowBatches.clear();
    }
}
