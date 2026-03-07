/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Phase 7: Vulkan SSAO pipeline — computes and applies screen-space
 *    ambient occlusion to DH's LOD scene before compositing onto MC.
 */

package com.seibel.distanthorizons.fabric.vulkan;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.util.math.Mat4f;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.VertexBuffer;
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
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Self-contained Vulkan SSAO pipeline that computes and applies screen-space
 * ambient occlusion to DH's rendered LOD scene.
 * <p>
 * <b>Pass 1</b> (Occlusion): Reads DH's depth texture, computes raw occlusion
 * via spiral depth sampling, and writes to an intermediate R16F texture.
 * <p>
 * <b>Pass 2</b> (Apply): Reads the raw SSAO texture and DH's depth texture,
 * applies bilateral Gaussian blur, and blends the result multiplicatively
 * onto DH's color buffer.
 * <p>
 * Follows the same patterns as {@link DhCompositePipeline}.
 */
public class DhSsaoPipeline {

    // Vulkan constants (inlined to avoid compile-time LWJGL dependency)
    private static final int VK_SHADER_STAGE_VERTEX_BIT = 0x00000001;
    private static final int VK_SHADER_STAGE_FRAGMENT_BIT = 0x00000010;
    private static final int VK_FORMAT_R16_SFLOAT = 76;
    private static final int VK_ATTACHMENT_LOAD_OP_CLEAR = 1;
    private static final int VK_ATTACHMENT_STORE_OP_STORE = 0;
    private static final int VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL = 5;

    // VTextureSelector slots for SSAO textures
    // Using slots 5-7 to avoid conflict with DH composite (3-4) and lightmap (2)
    private static final int SSAO_DEPTH_TEXTURE_SLOT = 5;
    private static final int SSAO_RAW_TEXTURE_SLOT = 6;
    private static final int SSAO_APPLY_DEPTH_TEXTURE_SLOT = 7;

    /** Fullscreen quad vertex format: vec2 position */
    private static final VertexFormat QUAD_FORMAT;
    static {
        VertexFormatElement position = new VertexFormatElement(0, 0,
                VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.POSITION, 2);
        QUAD_FORMAT = VertexFormat.builder()
                .add("Position", position)
                .build();
    }

    // Pipelines
    private GraphicsPipeline ssaoComputePipeline;
    private GraphicsPipeline ssaoApplyPipeline;

    // Intermediate SSAO framebuffer (color-only, R16F, half-resolution)
    private Framebuffer ssaoFramebuffer;
    private RenderPass ssaoRenderPass;

    // Cached render pass for applying SSAO onto DH's color buffer (avoids per-frame
    // creation)
    private RenderPass applyRenderPass;

    // Shared fullscreen quad buffers
    private VertexBuffer quadVertexBuffer;
    private IndexBuffer quadIndexBuffer;

    // Uniform buffers for pass 1 (occlusion computation)
    private final Map<String, MappedBuffer> pass1Uniforms = new HashMap<>();
    // Uniform buffers for pass 2 (blur + apply)
    private final Map<String, MappedBuffer> pass2Uniforms = new HashMap<>();

    private int width;
    private int height;
    private boolean initialized = false;

    /**
     * Initialize the SSAO pipeline at the given framebuffer dimensions.
     * Must be called from the render thread.
     */
    public void init(int width, int height) {
        if (this.initialized) {
            return;
        }

        this.width = width;
        this.height = height;

        createQuadBuffers();
        createSsaoFramebuffer();
        createSsaoComputePipeline();
        createSsaoApplyPipeline();

        // Register resize callback
        Renderer.getInstance().addOnResizeCallback(this::onResize);

        this.initialized = true;
    }

    // ==================== //
    // Quad Buffer Creation //
    // ==================== //

    private void createQuadBuffers() {
        // 4 vertices × 2 floats × 4 bytes = 32 bytes
        ByteBuffer vertexData = ByteBuffer.allocateDirect(32);
        vertexData.order(ByteOrder.nativeOrder());
        vertexData.putFloat(-1.0f);
        vertexData.putFloat(-1.0f); // bottom-left
        vertexData.putFloat(1.0f);
        vertexData.putFloat(-1.0f); // bottom-right
        vertexData.putFloat(1.0f);
        vertexData.putFloat(1.0f); // top-right
        vertexData.putFloat(-1.0f);
        vertexData.putFloat(1.0f); // top-left
        vertexData.flip();

        this.quadVertexBuffer = new VertexBuffer(vertexData.remaining(), MemoryTypes.GPU_MEM);
        this.quadVertexBuffer.copyBuffer(vertexData, vertexData.remaining());

        // 6 indices for 2 triangles
        ByteBuffer indexData = ByteBuffer.allocateDirect(6 * 4);
        indexData.order(ByteOrder.nativeOrder());
        indexData.putInt(0);
        indexData.putInt(1);
        indexData.putInt(2);
        indexData.putInt(2);
        indexData.putInt(3);
        indexData.putInt(0);
        indexData.flip();

        this.quadIndexBuffer = new IndexBuffer(indexData.remaining(), MemoryTypes.GPU_MEM,
                IndexBuffer.IndexType.UINT32);
        this.quadIndexBuffer.copyBuffer(indexData, indexData.remaining());
    }

    // ========================== //
    // Intermediate SSAO Texture //
    // ========================== //

    private void createSsaoFramebuffer() {
        // Full-resolution R16F framebuffer for raw occlusion values
        this.ssaoFramebuffer = new Framebuffer.Builder(this.width, this.height, 1, false)
                .setFormat(VK_FORMAT_R16_SFLOAT)
                .setLinearFiltering(true)
                .build();

        // Render pass: clear color, store, final layout = SHADER_READ_ONLY for sampling
        // in pass 2
        RenderPass.Builder rpBuilder = RenderPass.builder(this.ssaoFramebuffer);
        rpBuilder.getColorAttachmentInfo()
                .setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE)
                .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        this.ssaoRenderPass = rpBuilder.build();
    }

    // ====================== //
    // Pass 1: SSAO Compute //
    // ====================== //

    private void createSsaoComputePipeline() {
        String vertSource = readShaderResource("shaders/vulkan/dh_ssao.vert");
        String fragSource = readShaderResource("shaders/vulkan/dh_ssao.frag");

        Pipeline.Builder builder = new Pipeline.Builder(QUAD_FORMAT);
        builder.compileShaders("dh_ssao_compute", vertSource, fragSource);

        // UBO at binding 0: SSAO parameters
        List<UBO> ubos = new ArrayList<>();
        AlignedStruct.Builder uboBuilder = new AlignedStruct.Builder();

        addUniform(uboBuilder, this.pass1Uniforms, "matrix4x4", "uInvProj", 1, 64);
        addUniform(uboBuilder, this.pass1Uniforms, "matrix4x4", "uProj", 1, 64);
        addUniform(uboBuilder, this.pass1Uniforms, "int", "uSampleCount", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uRadius", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uStrength", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uMinLight", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uBias", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uFadeDistanceInBlocks", 1, 4);

        UBO mainUbo = uboBuilder.buildUBO(0, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        ubos.add(mainUbo);

        // Image descriptor: DH depth at binding 1
        List<ImageDescriptor> imageDescriptors = new ArrayList<>();
        imageDescriptors.add(new ImageDescriptor(1, "sampler2D", "uDepthMap", SSAO_DEPTH_TEXTURE_SLOT));

        builder.setUniforms(ubos, imageDescriptors);
        this.ssaoComputePipeline = builder.createGraphicsPipeline();
    }

    // ====================== //
    // Pass 2: SSAO Apply //
    // ====================== //

    private void createSsaoApplyPipeline() {
        String vertSource = readShaderResource("shaders/vulkan/dh_ssao.vert");
        String fragSource = readShaderResource("shaders/vulkan/dh_ssao_apply.frag");

        Pipeline.Builder builder = new Pipeline.Builder(QUAD_FORMAT);
        builder.compileShaders("dh_ssao_apply", vertSource, fragSource);

        // UBO at binding 0: blur parameters
        List<UBO> ubos = new ArrayList<>();
        AlignedStruct.Builder uboBuilder = new AlignedStruct.Builder();

        addUniform(uboBuilder, this.pass2Uniforms, "float", "gViewSize", 2, 8); // vec2
        addUniform(uboBuilder, this.pass2Uniforms, "int", "gBlurRadius", 1, 4);
        addUniform(uboBuilder, this.pass2Uniforms, "float", "gNear", 1, 4);
        addUniform(uboBuilder, this.pass2Uniforms, "float", "gFar", 1, 4);
        addUniform(uboBuilder, this.pass2Uniforms, "int", "uDebugMode", 1, 4);

        UBO mainUbo = uboBuilder.buildUBO(0, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        ubos.add(mainUbo);

        // Image descriptors: raw SSAO at binding 1, DH depth at binding 2
        List<ImageDescriptor> imageDescriptors = new ArrayList<>();
        imageDescriptors.add(new ImageDescriptor(1, "sampler2D", "gSSAOMap", SSAO_RAW_TEXTURE_SLOT));
        imageDescriptors.add(new ImageDescriptor(2, "sampler2D", "gDepthMap", SSAO_APPLY_DEPTH_TEXTURE_SLOT));

        builder.setUniforms(ubos, imageDescriptors);
        this.ssaoApplyPipeline = builder.createGraphicsPipeline();
    }

    // ============ //
    // Render Call //
    // ============ //

    /**
     * Execute the full SSAO pipeline: pass 1 (occlusion) + pass 2 (blur/apply).
     * <p>
     * Must be called after DH's LOD render pass has ended and its attachments
     * are in SHADER_READ_ONLY layout, but <b>before</b> the composite pass.
     *
     * @param dhFramebuffer    the DH framebuffer containing color + depth from LOD
     *                         rendering
     * @param projectionMatrix DH's projection matrix for depth reconstruction
     */
    public void render(DhVulkanFramebuffer dhFramebuffer, Mat4f projectionMatrix) {
        if (!this.initialized) {
            return;
        }

        VulkanImage dhDepthTexture = dhFramebuffer.getFramebuffer().getDepthAttachment();
        VulkanImage dhColorTexture = dhFramebuffer.getFramebuffer().getColorAttachment();

        // ===================== //
        // Pass 1: Compute SSAO //
        // ===================== //

        // Fill pass 1 uniforms
        Mat4f invProj = new Mat4f(projectionMatrix);
        invProj.invert();
        setUniformMat4(this.pass1Uniforms, "uInvProj", invProj);
        setUniformMat4(this.pass1Uniforms, "uProj", projectionMatrix);
        setUniformInt(this.pass1Uniforms, "uSampleCount", 4); // reduced from 6 for perf
        setUniformFloat(this.pass1Uniforms, "uRadius", 4.0f);
        // Tuned down vs GL path — MC projection gives sharper depth gradients
        // than DH projection, producing stronger occlusion from correct normals.
        setUniformFloat(this.pass1Uniforms, "uStrength", 0.18f);
        setUniformFloat(this.pass1Uniforms, "uMinLight", 0.30f);
        setUniformFloat(this.pass1Uniforms, "uBias", 0.025f);
        setUniformFloat(this.pass1Uniforms, "uFadeDistanceInBlocks", 1600.0f);

        // Bind DH depth texture for sampling
        VTextureSelector.bindTexture(SSAO_DEPTH_TEXTURE_SLOT, dhDepthTexture);

        // Save and set state: no blend, no depth test, no cull
        boolean prevCull = VRenderSystem.cull;
        boolean prevDepthMask = VRenderSystem.depthMask;
        int prevDepthFun = VRenderSystem.depthFun;
        boolean prevBlend = PipelineState.blendInfo.enabled;
        boolean prevDepthTest = VRenderSystem.depthTest;

        VRenderSystem.cull = false;
        VRenderSystem.depthMask = false;
        VRenderSystem.depthTest = false;
        VRenderSystem.depthFun = 519; // GL_ALWAYS
        PipelineState.blendInfo.enabled = false;

        // Begin SSAO render pass (renders into intermediate R16F texture)
        Renderer.getInstance().beginRenderPass(this.ssaoRenderPass, this.ssaoFramebuffer);

        Renderer.getInstance().bindGraphicsPipeline(this.ssaoComputePipeline);
        Renderer.getInstance().uploadAndBindUBOs(this.ssaoComputePipeline);
        Renderer.getDrawer().drawIndexed(this.quadVertexBuffer, this.quadIndexBuffer, 6);

        // End SSAO render pass — transitions SSAO texture to SHADER_READ_ONLY
        Renderer.getInstance().endRenderPass();

        // ====================== //
        // Pass 2: Blur + Apply //
        // ====================== //

        // Fill pass 2 uniforms (use full resolution for blur sampling)
        setUniformVec2(this.pass2Uniforms, "gViewSize", this.width, this.height);
        setUniformInt(this.pass2Uniforms, "gBlurRadius", 0); // skip blur — bilinear filtering suffices
        setUniformFloat(this.pass2Uniforms, "gNear", RenderUtil.getNearClipPlaneInBlocks());
        setUniformFloat(this.pass2Uniforms, "gFar", RenderUtil.getFarClipPlaneDistanceInBlocks());
        setUniformInt(this.pass2Uniforms, "uDebugMode", 0);

        // Bind raw SSAO texture + DH depth for the apply pass
        VTextureSelector.bindTexture(SSAO_RAW_TEXTURE_SLOT, this.ssaoFramebuffer.getColorAttachment());
        VTextureSelector.bindTexture(SSAO_APPLY_DEPTH_TEXTURE_SLOT, dhDepthTexture);

        // Multiplicative blend — GL equivalent: glBlendFuncSeparate(GL_ZERO,
        // GL_SRC_ALPHA, GL_ZERO, GL_ONE)
        PipelineState.blendInfo.enabled = true;
        PipelineState.blendInfo.srcRgbFactor = 0; // VK_BLEND_FACTOR_ZERO
        PipelineState.blendInfo.dstRgbFactor = 6; // VK_BLEND_FACTOR_SRC_ALPHA
        PipelineState.blendInfo.srcAlphaFactor = 0; // VK_BLEND_FACTOR_ZERO
        PipelineState.blendInfo.dstAlphaFactor = 1; // VK_BLEND_FACTOR_ONE
        PipelineState.blendInfo.blendOp = 0; // VK_BLEND_OP_ADD

        // Use cached apply render pass (created once, reused every frame)
        if (this.applyRenderPass == null) {
            RenderPass.Builder applyRpBuilder = RenderPass.builder(dhFramebuffer.getFramebuffer());
            applyRpBuilder.getColorAttachmentInfo()
                    .setOps(0 /* VK_ATTACHMENT_LOAD_OP_LOAD */, VK_ATTACHMENT_STORE_OP_STORE)
                    .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            applyRpBuilder.getDepthAttachmentInfo()
                    .setOps(0 /* VK_ATTACHMENT_LOAD_OP_LOAD */, VK_ATTACHMENT_STORE_OP_STORE)
                    .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            this.applyRenderPass = applyRpBuilder.build();
        }

        Renderer.getInstance().beginRenderPass(this.applyRenderPass, dhFramebuffer.getFramebuffer());

        Renderer.getInstance().bindGraphicsPipeline(this.ssaoApplyPipeline);
        Renderer.getInstance().uploadAndBindUBOs(this.ssaoApplyPipeline);
        Renderer.getDrawer().drawIndexed(this.quadVertexBuffer, this.quadIndexBuffer, 6);

        Renderer.getInstance().endRenderPass();

        // Restore state
        VRenderSystem.cull = prevCull;
        VRenderSystem.depthMask = prevDepthMask;
        VRenderSystem.depthTest = prevDepthTest;
        VRenderSystem.depthFun = prevDepthFun;
        PipelineState.blendInfo.enabled = prevBlend;
    }

    // ========== //
    // Resizing //
    // ========== //

    private void onResize() {
        int newWidth = Renderer.getInstance().getSwapChain().getWidth();
        int newHeight = Renderer.getInstance().getSwapChain().getHeight();

        if (newWidth == 0 || newHeight == 0) {
            return; // Minimized window
        }
        if (newWidth == this.width && newHeight == this.height) {
            return; // No change
        }

        // Clean up old SSAO framebuffer and cached apply render pass
        if (this.ssaoRenderPass != null) {
            this.ssaoRenderPass.cleanUp();
        }
        if (this.ssaoFramebuffer != null) {
            this.ssaoFramebuffer.cleanUp();
        }
        if (this.applyRenderPass != null) {
            this.applyRenderPass.cleanUp();
            this.applyRenderPass = null;
        }

        this.width = newWidth;
        this.height = newHeight;

        createSsaoFramebuffer();
    }

    // ========== //
    // Cleanup //
    // ========== //

    /**
     * Returns the intermediate SSAO texture for debug visualization. May be null.
     */
    public VulkanImage getIntermediateTexture() {
        return this.ssaoFramebuffer != null ? this.ssaoFramebuffer.getColorAttachment() : null;
    }

    public void cleanup() {
        if (this.quadVertexBuffer != null) {
            this.quadVertexBuffer.scheduleFree();
            this.quadVertexBuffer = null;
        }
        if (this.quadIndexBuffer != null) {
            this.quadIndexBuffer.scheduleFree();
            this.quadIndexBuffer = null;
        }
        if (this.ssaoComputePipeline != null) {
            this.ssaoComputePipeline.cleanUp();
            this.ssaoComputePipeline = null;
        }
        if (this.ssaoApplyPipeline != null) {
            this.ssaoApplyPipeline.cleanUp();
            this.ssaoApplyPipeline = null;
        }
        if (this.ssaoRenderPass != null) {
            this.ssaoRenderPass.cleanUp();
            this.ssaoRenderPass = null;
        }
        if (this.applyRenderPass != null) {
            this.applyRenderPass.cleanUp();
            this.applyRenderPass = null;
        }
        if (this.ssaoFramebuffer != null) {
            this.ssaoFramebuffer.cleanUp();
            this.ssaoFramebuffer = null;
        }

        // Free MappedBuffers
        for (MappedBuffer mb : this.pass1Uniforms.values()) {
            MemoryUtil.memFree(mb.buffer);
        }
        this.pass1Uniforms.clear();
        for (MappedBuffer mb : this.pass2Uniforms.values()) {
            MemoryUtil.memFree(mb.buffer);
        }
        this.pass2Uniforms.clear();

        this.initialized = false;
    }

    // ================ //
    // Uniform Helpers //
    // ================ //

    private void addUniform(AlignedStruct.Builder builder, Map<String, MappedBuffer> uniforms,
            String type, String name, int count, int byteSize) {
        Uniform.Info info = Uniform.createUniformInfo(type, name, count);
        MappedBuffer mb = new MappedBuffer(byteSize);
        uniforms.put(name, mb);
        info.setBufferSupplier(() -> mb);
        builder.addUniformInfo(info);
    }

    private void setUniformMat4(Map<String, MappedBuffer> uniforms, String name, Mat4f matrix) {
        MappedBuffer mb = uniforms.get(name);
        if (mb == null)
            return;
        // Column-major for std140
        mb.putFloat(0, matrix.m00);
        mb.putFloat(4, matrix.m10);
        mb.putFloat(8, matrix.m20);
        mb.putFloat(12, matrix.m30);
        mb.putFloat(16, matrix.m01);
        mb.putFloat(20, matrix.m11);
        mb.putFloat(24, matrix.m21);
        mb.putFloat(28, matrix.m31);
        mb.putFloat(32, matrix.m02);
        mb.putFloat(36, matrix.m12);
        mb.putFloat(40, matrix.m22);
        mb.putFloat(44, matrix.m32);
        mb.putFloat(48, matrix.m03);
        mb.putFloat(52, matrix.m13);
        mb.putFloat(56, matrix.m23);
        mb.putFloat(60, matrix.m33);
    }

    private void setUniformFloat(Map<String, MappedBuffer> uniforms, String name, float value) {
        MappedBuffer mb = uniforms.get(name);
        if (mb != null)
            mb.putFloat(0, value);
    }

    private void setUniformInt(Map<String, MappedBuffer> uniforms, String name, int value) {
        MappedBuffer mb = uniforms.get(name);
        if (mb != null)
            mb.putInt(0, value);
    }

    private void setUniformVec2(Map<String, MappedBuffer> uniforms, String name, float x, float y) {
        MappedBuffer mb = uniforms.get(name);
        if (mb != null) {
            mb.putFloat(0, x);
            mb.putFloat(4, y);
        }
    }

    // ================ //
    // Shader Loading //
    // ================ //

    private static String readShaderResource(String path) {
        try (InputStream is = DhSsaoPipeline.class.getClassLoader().getResourceAsStream(path)) {
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
