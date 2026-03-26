/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Composite pipeline for Phase 6 — composites DH's framebuffer onto MC's.
 */

package com.braffolk.dhvulkan.core.pipeline;

import com.braffolk.dhvulkan.compat.Compat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages the fullscreen-quad composite pipeline that blends DH's rendered
 * framebuffer onto MC's active render target.
 * <p>
 * The pipeline reads DH's color and depth textures (bound to VTextureSelector
 * slots 3 and 4) and writes both color and depth, discarding fragments where
 * DH didn't render (depth == 1.0).
 */
public class DhCompositePipeline {
    private static final DhLogger LOGGER = new DhLoggerBuilder().build();

    // Vulkan constants (from VK10) — inlined to avoid compile-time LWJGL dependency
    private static final int VK_SHADER_STAGE_VERTEX_BIT = 0x00000001;
    private static final int VK_SHADER_STAGE_FRAGMENT_BIT = 0x00000010;

    /** VTextureSelector slot for DH color texture */
    static final int DH_COLOR_TEXTURE_SLOT = 3;
    /** VTextureSelector slot for DH depth texture */
    static final int DH_DEPTH_TEXTURE_SLOT = 4;
    /** VTextureSelector slot for SSAO intermediate texture (debug) */
    static final int DEBUG_SSAO_TEXTURE_SLOT = 5;
    /** VTextureSelector slot for fog intermediate texture (debug) */
    static final int DEBUG_FOG_TEXTURE_SLOT = 6;
    /**
     * VTextureSelector slot for MC's depth texture (for depth-compared composite)
     */
    static final int MC_DEPTH_TEXTURE_SLOT = 7;

    private GraphicsPipeline compositePipeline;
    private Object quadVertexBuffer;

    private boolean initialized = false;

    // Persistent uniform buffers
    private MappedBuffer invProjBuf;
    private MappedBuffer mcProjBuf;
    private MappedBuffer invMcMvmProjBuf;
    private MappedBuffer remapProjBuf;
    private MappedBuffer debugModeBuf;
    private MappedBuffer useMcDepthBuf;
    private MappedBuffer startFadeDistBuf;
    private MappedBuffer endFadeDistBuf;
    private MappedBuffer startFadeDistSqBuf;
    private MappedBuffer endFadeDistSqBuf;

    /**
     * Simple vertex format for the fullscreen quad: just vec2 position.
     */
    private static final VertexFormat QUAD_FORMAT;
    static {
        VertexFormatElement position = Compat.vertexFormatElement(0, 0,
                VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.POSITION, 2);
        QUAD_FORMAT = Compat.buildVertexFormat(
                new String[] { "Position" },
                new VertexFormatElement[] { position });
    }

    public void init() {
        if (this.initialized) {
            return;
        }

        createQuadBuffers();
        createPipeline();
        this.initialized = true;
        LOGGER.info("[DH-Vulkan] DhCompositePipeline initialized.");
    }

    /**
     * Creates a single fullscreen triangle: 3 vertices forming an oversized
     * triangle that fully covers the [-1,1]×[-1,1] NDC range. The GPU clips
     * the excess — this is the Vulkan best practice for fullscreen passes,
     * avoiding two-triangle issues entirely.
     */
    private void createQuadBuffers() {
        // 3 vertices × 2 floats × 4 bytes = 24 bytes
        ByteBuffer vertexData = ByteBuffer.allocateDirect(24);
        vertexData.order(ByteOrder.nativeOrder());
        vertexData.putFloat(-1.0f);
        vertexData.putFloat(-1.0f); // bottom-left
        vertexData.putFloat(3.0f);
        vertexData.putFloat(-1.0f); // far right (clipped)
        vertexData.putFloat(-1.0f);
        vertexData.putFloat(3.0f); // far top (clipped)
        vertexData.flip();

        this.quadVertexBuffer = Compat.createGpuVertexBuffer(vertexData.remaining());
        Compat.copyBuffer(this.quadVertexBuffer, vertexData, vertexData.remaining());

        // No index buffer needed for 3-vertex draw
    }

    /**
     * Creates the composite graphics pipeline:
     * - Vertex shader: fullscreen quad
     * - Fragment shader: samples DH color + depth, writes both with bias
     */
    private void createPipeline() {
        String vertSource = readShaderResource("shaders/vulkan/dh_apply.vert");
        String fragSource = readShaderResource("shaders/vulkan/dh_apply.frag");

        Pipeline.Builder builder = new Pipeline.Builder(QUAD_FORMAT);
        builder.compileShaders("dh_composite", vertSource, fragSource);

        // UBO at binding 0: mat4 uInvProj (64 bytes) + int uDebugMode (4 bytes) + int
        // uUseMcDepth (4 bytes)
        List<UBO> ubos = new ArrayList<>();
        AlignedStruct.Builder uboBuilder = new AlignedStruct.Builder();

        this.invProjBuf = new MappedBuffer(64);
        Compat.addUniformWithBuffer(uboBuilder, "matrix4x4", "uInvProj", 1, () -> this.invProjBuf);

        this.mcProjBuf = new MappedBuffer(64);
        Compat.addUniformWithBuffer(uboBuilder, "matrix4x4", "uMcProj", 1, () -> this.mcProjBuf);

        this.invMcMvmProjBuf = new MappedBuffer(64);
        Compat.addUniformWithBuffer(uboBuilder, "matrix4x4", "uInvMcMvmProj", 1, () -> this.invMcMvmProjBuf);

        this.remapProjBuf = new MappedBuffer(64);
        Compat.addUniformWithBuffer(uboBuilder, "matrix4x4", "uRemapProj", 1, () -> this.remapProjBuf);

        this.debugModeBuf = new MappedBuffer(4);
        this.debugModeBuf.putInt(0, 0);
        Compat.addUniformWithBuffer(uboBuilder, "int", "uDebugMode", 1, () -> this.debugModeBuf);

        this.useMcDepthBuf = new MappedBuffer(4);
        this.useMcDepthBuf.putInt(0, 0);
        Compat.addUniformWithBuffer(uboBuilder, "int", "uUseMcDepth", 1, () -> this.useMcDepthBuf);

        this.startFadeDistBuf = new MappedBuffer(4);
        this.startFadeDistBuf.putFloat(0, 0);
        Compat.addUniformWithBuffer(uboBuilder, "float", "uStartFadeBlockDist", 1, () -> this.startFadeDistBuf);

        this.endFadeDistBuf = new MappedBuffer(4);
        this.endFadeDistBuf.putFloat(0, 0);
        Compat.addUniformWithBuffer(uboBuilder, "float", "uEndFadeBlockDist", 1, () -> this.endFadeDistBuf);

        this.startFadeDistSqBuf = new MappedBuffer(4);
        this.startFadeDistSqBuf.putFloat(0, 0);
        Compat.addUniformWithBuffer(uboBuilder, "float", "uStartFadeBlockDistSq", 1, () -> this.startFadeDistSqBuf);

        this.endFadeDistSqBuf = new MappedBuffer(4);
        this.endFadeDistSqBuf.putFloat(0, 0);
        Compat.addUniformWithBuffer(uboBuilder, "float", "uEndFadeBlockDistSq", 1, () -> this.endFadeDistSqBuf);

        UBO mainUbo = uboBuilder.buildUBO(0, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        Compat.setUniformSuppliers(mainUbo, java.util.Map.ofEntries(
                java.util.Map.entry("uInvProj", this.invProjBuf),
                java.util.Map.entry("uMcProj", this.mcProjBuf),
                java.util.Map.entry("uInvMcMvmProj", this.invMcMvmProjBuf),
                java.util.Map.entry("uRemapProj", this.remapProjBuf),
                java.util.Map.entry("uDebugMode", this.debugModeBuf),
                java.util.Map.entry("uUseMcDepth", this.useMcDepthBuf),
                java.util.Map.entry("uStartFadeBlockDist", this.startFadeDistBuf),
                java.util.Map.entry("uEndFadeBlockDist", this.endFadeDistBuf),
                java.util.Map.entry("uStartFadeBlockDistSq", this.startFadeDistSqBuf),
                java.util.Map.entry("uEndFadeBlockDistSq", this.endFadeDistSqBuf)));
        ubos.add(mainUbo);

        // Image descriptors — DH color/depth + SSAO/fog debug + MC depth
        List<ImageDescriptor> imageDescriptors = new ArrayList<>();
        imageDescriptors.add(new ImageDescriptor(1, "sampler2D", "gDhColorTexture", DH_COLOR_TEXTURE_SLOT));
        imageDescriptors.add(new ImageDescriptor(2, "sampler2D", "gDhDepthTexture", DH_DEPTH_TEXTURE_SLOT));
        imageDescriptors.add(new ImageDescriptor(3, "sampler2D", "gSsaoTexture", DEBUG_SSAO_TEXTURE_SLOT));
        imageDescriptors.add(new ImageDescriptor(4, "sampler2D", "gFogTexture", DEBUG_FOG_TEXTURE_SLOT));
        imageDescriptors.add(new ImageDescriptor(5, "sampler2D", "gMcDepthTexture", MC_DEPTH_TEXTURE_SLOT));

        builder.setUniforms(ubos, imageDescriptors);
        this.compositePipeline = builder.createGraphicsPipeline();

    }

    /**
     * Draws the fullscreen composite quad. Must be called while MC's render pass
     * is active and after binding DH's framebuffer textures to the
     * VTextureSelector slots.
     *
     * @param mcDepthTexture MC's depth attachment for depth comparison, or null to
     *                       skip.
     *                       When non-null, LOD fragments are discarded where MC
     *                       terrain is closer.
     */
    public void render(VulkanImage dhColorTexture, VulkanImage dhDepthTexture,
            VulkanImage ssaoTexture, VulkanImage fogTexture,
            VulkanImage mcDepthTexture,
            int debugMode, float[] invProjMatrix, float[] mcProjMatrix,
            float[] invMcMvmProjMatrix, float startFadeDist, float endFadeDist) {
        if (!this.initialized) {
            return;
        }

        // Update uniforms
        this.debugModeBuf.putInt(0, debugMode);
        this.useMcDepthBuf.putInt(0, mcDepthTexture != null ? 1 : 0);
        if (invProjMatrix != null && invProjMatrix.length == 16) {
            for (int i = 0; i < 16; i++) {
                this.invProjBuf.putFloat(i * 4, invProjMatrix[i]);
            }
        }
        if (mcProjMatrix != null && mcProjMatrix.length == 16) {
            for (int i = 0; i < 16; i++) {
                this.mcProjBuf.putFloat(i * 4, mcProjMatrix[i]);
            }
        }
        if (invMcMvmProjMatrix != null && invMcMvmProjMatrix.length == 16) {
            for (int i = 0; i < 16; i++) {
                this.invMcMvmProjBuf.putFloat(i * 4, invMcMvmProjMatrix[i]);
            }
        }
        this.startFadeDistBuf.putFloat(0, startFadeDist);
        this.endFadeDistBuf.putFloat(0, endFadeDist);
        this.startFadeDistSqBuf.putFloat(0, startFadeDist * startFadeDist);
        this.endFadeDistSqBuf.putFloat(0, endFadeDist * endFadeDist);

        // Compute fused remap matrix: uRemapProj = mcProj * invProj
        // This saves one mat4×vec4 per pixel in the shader
        if (invProjMatrix != null && mcProjMatrix != null) {
            float[] remapProj = new float[16];
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < 4; col++) {
                    float sum = 0;
                    for (int k = 0; k < 4; k++) {
                        sum += mcProjMatrix[k * 4 + row] * invProjMatrix[col * 4 + k];
                    }
                    remapProj[col * 4 + row] = sum;
                }
            }
            for (int i = 0; i < 16; i++) {
                this.remapProjBuf.putFloat(i * 4, remapProj[i]);
            }
        }

        // Bind DH framebuffer textures to the expected slots
        VTextureSelector.bindTexture(DH_COLOR_TEXTURE_SLOT, dhColorTexture);
        VTextureSelector.bindTexture(DH_DEPTH_TEXTURE_SLOT, dhDepthTexture);

        // VM 0.4.2 requires ALL image descriptor slots to have valid images —
        // null causes NPE in Pipeline.updateDescriptorSet(). Use white texture
        // fallback.
        VulkanImage fallback = VTextureSelector.getWhiteTexture();
        VTextureSelector.bindTexture(DEBUG_SSAO_TEXTURE_SLOT, ssaoTexture != null ? ssaoTexture : fallback);
        VTextureSelector.bindTexture(DEBUG_FOG_TEXTURE_SLOT, fogTexture != null ? fogTexture : fallback);
        // MC depth needs a depth-only image view on combined D+S formats (NVIDIA
        // Windows)
        if (mcDepthTexture != null) {
            Compat.prepareMcDepthForSampling(MC_DEPTH_TEXTURE_SLOT, mcDepthTexture);
        } else {
            VTextureSelector.bindTexture(MC_DEPTH_TEXTURE_SLOT, fallback);
        }

        // Set pipeline state for composite: premultiplied alpha blend, no cull, depth
        // write. depthTest MUST be true — without it, Vulkan ignores gl_FragDepth
        // writes entirely (per spec: depth attachment not modified when test disabled).
        boolean prevCull = VRenderSystem.cull;
        boolean prevDepthTest = VRenderSystem.depthTest;
        boolean prevDepthMask = VRenderSystem.depthMask;
        int prevDepthFun = VRenderSystem.depthFun;
        boolean prevBlend = PipelineState.blendInfo.enabled;
        int prevSrcRgb = PipelineState.blendInfo.srcRgbFactor;
        int prevDstRgb = PipelineState.blendInfo.dstRgbFactor;
        int prevSrcAlpha = PipelineState.blendInfo.srcAlphaFactor;
        int prevDstAlpha = PipelineState.blendInfo.dstAlphaFactor;
        int prevBlendOp = PipelineState.blendInfo.blendOp;

        VRenderSystem.cull = false;
        VRenderSystem.depthTest = true; // CRITICAL: enables gl_FragDepth writes
        VRenderSystem.depthMask = true;
        // GL_ALWAYS: the shader handles depth comparison via the MC depth texture.
        // The depth test always passes, but depth WRITES still happen via gl_FragDepth.
        // Open-sky LOD pixels are discarded in the shader to preserve weather.
        VRenderSystem.depthFun = 519; // GL_ALWAYS
        // Premultiplied alpha blending: DH's color buffer is already
        // alpha-premultiplied from DH's own transparent pass blending,
        // so use ONE (not SRC_ALPHA) to avoid double-multiplication.
        PipelineState.blendInfo.enabled = true;
        PipelineState.blendInfo.srcRgbFactor = 1; // VK_BLEND_FACTOR_ONE
        PipelineState.blendInfo.dstRgbFactor = 7; // VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
        PipelineState.blendInfo.srcAlphaFactor = 1; // VK_BLEND_FACTOR_ONE
        PipelineState.blendInfo.dstAlphaFactor = 7; // VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
        PipelineState.blendInfo.blendOp = 0; // VK_BLEND_OP_ADD

        // Bind pipeline and draw
        Renderer.getInstance().bindGraphicsPipeline(this.compositePipeline);
        Renderer.getInstance().uploadAndBindUBOs(this.compositePipeline);
        Compat.draw(this.quadVertexBuffer, 3);

        // Restore depth-only view swap (if any)
        Compat.restoreMcDepthView();

        // Restore state
        VRenderSystem.cull = prevCull;
        VRenderSystem.depthTest = prevDepthTest;
        VRenderSystem.depthMask = prevDepthMask;
        VRenderSystem.depthFun = prevDepthFun;
        PipelineState.blendInfo.enabled = prevBlend;
        PipelineState.blendInfo.srcRgbFactor = prevSrcRgb;
        PipelineState.blendInfo.dstRgbFactor = prevDstRgb;
        PipelineState.blendInfo.srcAlphaFactor = prevSrcAlpha;
        PipelineState.blendInfo.dstAlphaFactor = prevDstAlpha;
        PipelineState.blendInfo.blendOp = prevBlendOp;
    }

    public void cleanup() {
        if (this.quadVertexBuffer != null) {
            Compat.scheduleFree(this.quadVertexBuffer);
            this.quadVertexBuffer = null;
        }

        if (this.compositePipeline != null) {
            this.compositePipeline.cleanUp();
            this.compositePipeline = null;
        }

        // Free native MappedBuffers
        if (this.invProjBuf != null) {
            org.lwjgl.system.MemoryUtil.memFree(this.invProjBuf.buffer);
            this.invProjBuf = null;
        }
        if (this.mcProjBuf != null) {
            org.lwjgl.system.MemoryUtil.memFree(this.mcProjBuf.buffer);
            this.mcProjBuf = null;
        }
        if (this.debugModeBuf != null) {
            org.lwjgl.system.MemoryUtil.memFree(this.debugModeBuf.buffer);
            this.debugModeBuf = null;
        }
        if (this.useMcDepthBuf != null) {
            org.lwjgl.system.MemoryUtil.memFree(this.useMcDepthBuf.buffer);
            this.useMcDepthBuf = null;
        }
        if (this.invMcMvmProjBuf != null) {
            org.lwjgl.system.MemoryUtil.memFree(this.invMcMvmProjBuf.buffer);
            this.invMcMvmProjBuf = null;
        }
        if (this.startFadeDistBuf != null) {
            org.lwjgl.system.MemoryUtil.memFree(this.startFadeDistBuf.buffer);
            this.startFadeDistBuf = null;
        }
        if (this.endFadeDistBuf != null) {
            org.lwjgl.system.MemoryUtil.memFree(this.endFadeDistBuf.buffer);
            this.endFadeDistBuf = null;
        }
        if (this.remapProjBuf != null) {
            org.lwjgl.system.MemoryUtil.memFree(this.remapProjBuf.buffer);
            this.remapProjBuf = null;
        }
        if (this.startFadeDistSqBuf != null) {
            org.lwjgl.system.MemoryUtil.memFree(this.startFadeDistSqBuf.buffer);
            this.startFadeDistSqBuf = null;
        }
        if (this.endFadeDistSqBuf != null) {
            org.lwjgl.system.MemoryUtil.memFree(this.endFadeDistSqBuf.buffer);
            this.endFadeDistSqBuf = null;
        }

        this.initialized = false;
        LOGGER.info("[DH-Vulkan] DhCompositePipeline cleaned up.");
    }

    private static String readShaderResource(String path) {
        try (InputStream is = DhCompositePipeline.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new RuntimeException("[DH-Vulkan] Shader resource not found: " + path);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new RuntimeException("[DH-Vulkan] Failed to read shader: " + path, e);
        }
    }
}
