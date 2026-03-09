/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Composite pipeline for Phase 6 — composites DH's framebuffer onto MC's.
 */

package com.braffolk.dhvulkan;

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

    private GraphicsPipeline compositePipeline;
    private Object quadVertexBuffer;

    private boolean initialized = false;

    // Persistent uniform buffers
    private MappedBuffer invProjBuf;
    private MappedBuffer debugModeBuf;

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

        // UBO at binding 0: mat4 uInvProj (64 bytes) + int uDebugMode (4 bytes)
        List<UBO> ubos = new ArrayList<>();
        AlignedStruct.Builder uboBuilder = new AlignedStruct.Builder();

        this.invProjBuf = new MappedBuffer(64);
        Compat.addUniformWithBuffer(uboBuilder, "matrix4x4", "uInvProj", 1, () -> this.invProjBuf);

        this.debugModeBuf = new MappedBuffer(4);
        this.debugModeBuf.putInt(0, 0);
        Compat.addUniformWithBuffer(uboBuilder, "int", "uDebugMode", 1, () -> this.debugModeBuf);

        UBO mainUbo = uboBuilder.buildUBO(0, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        ubos.add(mainUbo);

        // Image descriptors — DH color/depth + SSAO/fog debug textures
        List<ImageDescriptor> imageDescriptors = new ArrayList<>();
        imageDescriptors.add(new ImageDescriptor(1, "sampler2D", "gDhColorTexture", DH_COLOR_TEXTURE_SLOT));
        imageDescriptors.add(new ImageDescriptor(2, "sampler2D", "gDhDepthTexture", DH_DEPTH_TEXTURE_SLOT));
        imageDescriptors.add(new ImageDescriptor(3, "sampler2D", "gSsaoTexture", DEBUG_SSAO_TEXTURE_SLOT));
        imageDescriptors.add(new ImageDescriptor(4, "sampler2D", "gFogTexture", DEBUG_FOG_TEXTURE_SLOT));

        builder.setUniforms(ubos, imageDescriptors);
        this.compositePipeline = builder.createGraphicsPipeline();
    }

    /**
     * Draws the fullscreen composite quad. Must be called while MC's render pass
     * is active and after binding DH's framebuffer textures to the
     * VTextureSelector slots.
     */
    public void render(VulkanImage dhColorTexture, VulkanImage dhDepthTexture,
            VulkanImage ssaoTexture, VulkanImage fogTexture,
            int debugMode, float[] invProjMatrix) {
        if (!this.initialized) {
            return;
        }

        // Update uniforms
        this.debugModeBuf.putInt(0, debugMode);
        if (invProjMatrix != null && invProjMatrix.length == 16) {
            for (int i = 0; i < 16; i++) {
                this.invProjBuf.putFloat(i * 4, invProjMatrix[i]);
            }
        }

        // Bind DH framebuffer textures to the expected slots
        VTextureSelector.bindTexture(DH_COLOR_TEXTURE_SLOT, dhColorTexture);
        VTextureSelector.bindTexture(DH_DEPTH_TEXTURE_SLOT, dhDepthTexture);

        // Bind debug textures (SSAO and fog intermediates)
        if (ssaoTexture != null) {
            VTextureSelector.bindTexture(DEBUG_SSAO_TEXTURE_SLOT, ssaoTexture);
        }
        if (fogTexture != null) {
            VTextureSelector.bindTexture(DEBUG_FOG_TEXTURE_SLOT, fogTexture);
        }

        // Set pipeline state for composite: premultiplied alpha blend, no cull, depth
        // write
        boolean prevCull = VRenderSystem.cull;
        boolean prevDepthMask = VRenderSystem.depthMask;
        int prevDepthFun = VRenderSystem.depthFun;
        boolean prevBlend = PipelineState.blendInfo.enabled;
        int prevSrcRgb = PipelineState.blendInfo.srcRgbFactor;
        int prevDstRgb = PipelineState.blendInfo.dstRgbFactor;
        int prevSrcAlpha = PipelineState.blendInfo.srcAlphaFactor;
        int prevDstAlpha = PipelineState.blendInfo.dstAlphaFactor;
        int prevBlendOp = PipelineState.blendInfo.blendOp;

        VRenderSystem.cull = false;
        VRenderSystem.depthMask = true;
        VRenderSystem.depthFun = 519; // GL_ALWAYS — MC depth buffer has DONT_CARE garbage
        // Premultiplied alpha blending: DH's color buffer is already
        // alpha-premultiplied
        // from DH's own transparent pass blending, so use ONE (not SRC_ALPHA) to avoid
        // double-multiplication. This correctly composites transparent LODs (water)
        // onto
        // MC's sky, while opaque LODs (alpha=1) fully overwrite.
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

        // Restore state
        VRenderSystem.cull = prevCull;
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
