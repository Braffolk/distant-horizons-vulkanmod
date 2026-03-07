/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Phase 7: Vulkan Fog pipeline — computes and applies distance-based
 *    and height-based fog to DH's LOD scene before compositing onto MC.
 */

package com.seibel.distanthorizons.fabric.vulkan;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.math.Mat4f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogColorMode;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogDirection;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogMixMode;
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

import java.awt.*;
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
 * Self-contained Vulkan Fog pipeline that computes and applies distance/height
 * fog
 * to DH's rendered LOD scene.
 * <p>
 * <b>Pass 1</b> (Compute): Reads DH's depth texture, reconstructs world
 * position,
 * computes far fog + height fog with configurable falloff (linear/exp/exp²),
 * writes fog color+alpha to intermediate RGBA16 texture.
 * <p>
 * <b>Pass 2</b> (Apply): Reads the fog texture and DH's depth, applies
 * depth-gated
 * fog with SRC_ALPHA / ONE_MINUS_SRC_ALPHA blend onto DH's color buffer.
 * <p>
 * Architecture mirrors {@link DhSsaoPipeline}.
 */
public class DhFogPipeline {

    private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE
            .get(IMinecraftRenderWrapper.class);
    private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);

    // Vulkan constants
    private static final int VK_SHADER_STAGE_VERTEX_BIT = 0x00000001;
    private static final int VK_SHADER_STAGE_FRAGMENT_BIT = 0x00000010;
    private static final int VK_FORMAT_R16G16B16A16_SFLOAT = 97;
    private static final int VK_ATTACHMENT_LOAD_OP_CLEAR = 1;
    private static final int VK_ATTACHMENT_STORE_OP_STORE = 0;
    private static final int VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL = 5;

    // VTextureSelector slots — offset from SSAO (5-7) to avoid collision
    private static final int FOG_DEPTH_TEXTURE_SLOT = 8;
    private static final int FOG_COLOR_TEXTURE_SLOT = 9;
    private static final int FOG_APPLY_DEPTH_TEXTURE_SLOT = 10;

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
    private GraphicsPipeline fogComputePipeline;
    private GraphicsPipeline fogApplyPipeline;

    // Intermediate fog framebuffer (RGBA16F for fog color + alpha)
    private Framebuffer fogFramebuffer;
    private RenderPass fogRenderPass;

    // Cached apply render pass
    private RenderPass applyRenderPass;

    // Shared fullscreen quad buffers
    private VertexBuffer quadVertexBuffer;
    private IndexBuffer quadIndexBuffer;

    // Uniform buffers
    private final Map<String, MappedBuffer> pass1Uniforms = new HashMap<>();
    private final Map<String, MappedBuffer> pass2Uniforms = new HashMap<>();

    private int width;
    private int height;
    private boolean initialized = false;

    public void init(int width, int height) {
        if (this.initialized)
            return;

        this.width = width;
        this.height = height;

        createQuadBuffers();
        createFogFramebuffer();
        createFogComputePipeline();
        createFogApplyPipeline();

        Renderer.getInstance().addOnResizeCallback(this::onResize);

        this.initialized = true;
    }

    // ==================== //
    // Quad Buffer Creation //
    // ==================== //

    private void createQuadBuffers() {
        ByteBuffer vertexData = ByteBuffer.allocateDirect(32);
        vertexData.order(ByteOrder.nativeOrder());
        vertexData.putFloat(-1.0f);
        vertexData.putFloat(-1.0f);
        vertexData.putFloat(1.0f);
        vertexData.putFloat(-1.0f);
        vertexData.putFloat(1.0f);
        vertexData.putFloat(1.0f);
        vertexData.putFloat(-1.0f);
        vertexData.putFloat(1.0f);
        vertexData.flip();

        this.quadVertexBuffer = new VertexBuffer(vertexData.remaining(), MemoryTypes.GPU_MEM);
        this.quadVertexBuffer.copyBuffer(vertexData, vertexData.remaining());

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
    // Intermediate Fog Texture //
    // ========================== //

    private void createFogFramebuffer() {
        this.fogFramebuffer = new Framebuffer.Builder(this.width, this.height, 1, false)
                .setFormat(VK_FORMAT_R16G16B16A16_SFLOAT)
                .setLinearFiltering(true)
                .build();

        RenderPass.Builder rpBuilder = RenderPass.builder(this.fogFramebuffer);
        rpBuilder.getColorAttachmentInfo()
                .setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE)
                .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        this.fogRenderPass = rpBuilder.build();
    }

    // ====================== //
    // Pass 1: Fog Compute //
    // ====================== //

    private void createFogComputePipeline() {
        String vertSource = readShaderResource("shaders/vulkan/dh_ssao.vert"); // reuse fullscreen quad vert
        String fragSource = readShaderResource("shaders/vulkan/dh_fog.frag");

        Pipeline.Builder builder = new Pipeline.Builder(QUAD_FORMAT);
        builder.compileShaders("dh_fog_compute", vertSource, fragSource);

        List<UBO> ubos = new ArrayList<>();
        AlignedStruct.Builder uboBuilder = new AlignedStruct.Builder();

        // mat4 uInvMvmProj
        addUniform(uboBuilder, this.pass1Uniforms, "matrix4x4", "uInvMvmProj", 1, 64);
        // vec4 uFogColor
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uFogColor", 4, 16);
        // float uFogScale
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uFogScale", 1, 4);
        // float uFogVerticalScale
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uFogVerticalScale", 1, 4);
        // int uFogDebugMode
        addUniform(uboBuilder, this.pass1Uniforms, "int", "uFogDebugMode", 1, 4);
        // int uFogFalloffType
        addUniform(uboBuilder, this.pass1Uniforms, "int", "uFogFalloffType", 1, 4);

        // far fog config
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uFarFogStart", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uFarFogLength", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uFarFogMin", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uFarFogRange", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uFarFogDensity", 1, 4);

        // height fog config
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uHeightFogStart", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uHeightFogLength", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uHeightFogMin", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uHeightFogRange", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uHeightFogDensity", 1, 4);

        // height fog flags
        addUniform(uboBuilder, this.pass1Uniforms, "int", "uHeightFogEnabled", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "int", "uHeightFogFalloffType", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "int", "uHeightBasedOnCamera", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uHeightFogBaseHeight", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "int", "uHeightFogAppliesUp", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "int", "uHeightFogAppliesDown", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "int", "uUseSphericalFog", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "int", "uHeightFogMixingMode", 1, 4);
        addUniform(uboBuilder, this.pass1Uniforms, "float", "uCameraBlockYPos", 1, 4);

        UBO mainUbo = uboBuilder.buildUBO(0, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        ubos.add(mainUbo);

        List<ImageDescriptor> imageDescriptors = new ArrayList<>();
        imageDescriptors.add(new ImageDescriptor(1, "sampler2D", "uDepthMap", FOG_DEPTH_TEXTURE_SLOT));

        builder.setUniforms(ubos, imageDescriptors);
        this.fogComputePipeline = builder.createGraphicsPipeline();
    }

    // ====================== //
    // Pass 2: Fog Apply //
    // ====================== //

    private void createFogApplyPipeline() {
        String vertSource = readShaderResource("shaders/vulkan/dh_ssao.vert");
        String fragSource = readShaderResource("shaders/vulkan/dh_fog_apply.frag");

        Pipeline.Builder builder = new Pipeline.Builder(QUAD_FORMAT);
        builder.compileShaders("dh_fog_apply", vertSource, fragSource);

        List<UBO> ubos = new ArrayList<>();
        AlignedStruct.Builder uboBuilder = new AlignedStruct.Builder();
        addUniform(uboBuilder, this.pass2Uniforms, "float", "gViewSize", 2, 8);

        UBO mainUbo = uboBuilder.buildUBO(0, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        ubos.add(mainUbo);

        List<ImageDescriptor> imageDescriptors = new ArrayList<>();
        imageDescriptors.add(new ImageDescriptor(1, "sampler2D", "uFogTexture", FOG_COLOR_TEXTURE_SLOT));
        imageDescriptors.add(new ImageDescriptor(2, "sampler2D", "uDepthTexture", FOG_APPLY_DEPTH_TEXTURE_SLOT));

        builder.setUniforms(ubos, imageDescriptors);
        this.fogApplyPipeline = builder.createGraphicsPipeline();
    }

    // ============ //
    // Render Call //
    // ============ //

    /**
     * Execute fog pipeline: pass 1 (compute fog) + pass 2 (apply fog).
     * Must be called after SSAO but before composite.
     */
    public void render(DhVulkanFramebuffer dhFramebuffer, Mat4f modelViewMatrix, Mat4f projectionMatrix,
            float partialTicks) {
        if (!this.initialized)
            return;

        VulkanImage dhDepthTexture = dhFramebuffer.getFramebuffer().getDepthAttachment();

        // ===================== //
        // Pass 1: Compute Fog //
        // ===================== //

        // Inverse model-view-projection for world position reconstruction
        Mat4f mvpMatrix = new Mat4f(projectionMatrix);
        mvpMatrix.multiply(modelViewMatrix);
        Mat4f invMvpMatrix = new Mat4f(mvpMatrix);
        invMvpMatrix.invert();
        setUniformMat4(this.pass1Uniforms, "uInvMvmProj", invMvpMatrix);

        // Fog color
        Color fogColor;
        EDhApiFogColorMode colorMode = Config.Client.Advanced.Graphics.Fog.colorMode.get();
        if (colorMode == EDhApiFogColorMode.USE_SKY_COLOR) {
            fogColor = MC_RENDER.getSkyColor();
        } else {
            fogColor = MC_RENDER.getFogColor(partialTicks);
        }

        setUniformVec4(this.pass1Uniforms, "uFogColor",
                fogColor.getRed() / 255.0f, fogColor.getGreen() / 255.0f,
                fogColor.getBlue() / 255.0f, fogColor.getAlpha() / 255.0f);

        // Fog scales
        int lodDrawDistance = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get()
                * LodUtil.CHUNK_WIDTH;
        setUniformFloat(this.pass1Uniforms, "uFogScale", 1.0f / lodDrawDistance);
        setUniformFloat(this.pass1Uniforms, "uFogVerticalScale", 1.0f / MC.getWrappedClientLevel().getMaxHeight());
        setUniformInt(this.pass1Uniforms, "uFogDebugMode", 0);
        setUniformInt(this.pass1Uniforms, "uFogFalloffType",
                Config.Client.Advanced.Graphics.Fog.farFogFalloff.get().value);

        // Far fog config
        float farFogStart = Config.Client.Advanced.Graphics.Fog.farFogStart.get();
        float farFogEnd = Config.Client.Advanced.Graphics.Fog.farFogEnd.get();
        float farFogMin = Config.Client.Advanced.Graphics.Fog.farFogMin.get();
        float farFogMax = Config.Client.Advanced.Graphics.Fog.farFogMax.get();
        float farFogDensity = Config.Client.Advanced.Graphics.Fog.farFogDensity.get();

        // Override fog if underwater
        if (MC_RENDER.isFogStateSpecial()) {
            farFogStart = 0.0f;
            farFogEnd = 0.0f;
        }

        setUniformFloat(this.pass1Uniforms, "uFarFogStart", farFogStart);
        setUniformFloat(this.pass1Uniforms, "uFarFogLength", farFogEnd - farFogStart);
        setUniformFloat(this.pass1Uniforms, "uFarFogMin", farFogMin);
        setUniformFloat(this.pass1Uniforms, "uFarFogRange", farFogMax - farFogMin);
        setUniformFloat(this.pass1Uniforms, "uFarFogDensity", farFogDensity);

        // Height fog config
        EDhApiHeightFogMixMode heightFogMixingMode = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogMixMode
                .get();
        boolean heightFogEnabled = heightFogMixingMode != EDhApiHeightFogMixMode.SPHERICAL
                && heightFogMixingMode != EDhApiHeightFogMixMode.CYLINDRICAL;
        boolean useSphericalFog = heightFogMixingMode == EDhApiHeightFogMixMode.SPHERICAL;
        EDhApiHeightFogDirection heightFogDirection = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogDirection
                .get();

        float heightFogStart = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogStart.get();
        float heightFogEnd = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogEnd.get();
        float heightFogMin = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogMin.get();
        float heightFogMax = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogMax.get();
        float heightFogDensity = Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogDensity.get();

        setUniformFloat(this.pass1Uniforms, "uHeightFogStart", heightFogStart);
        setUniformFloat(this.pass1Uniforms, "uHeightFogLength", heightFogEnd - heightFogStart);
        setUniformFloat(this.pass1Uniforms, "uHeightFogMin", heightFogMin);
        setUniformFloat(this.pass1Uniforms, "uHeightFogRange", heightFogMax - heightFogMin);
        setUniformFloat(this.pass1Uniforms, "uHeightFogDensity", heightFogDensity);

        setUniformInt(this.pass1Uniforms, "uHeightFogEnabled", heightFogEnabled ? 1 : 0);
        setUniformInt(this.pass1Uniforms, "uHeightFogFalloffType",
                Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogFalloff.get().value);
        setUniformInt(this.pass1Uniforms, "uHeightBasedOnCamera", heightFogDirection.basedOnCamera ? 1 : 0);
        setUniformFloat(this.pass1Uniforms, "uHeightFogBaseHeight",
                Config.Client.Advanced.Graphics.Fog.HeightFog.heightFogBaseHeight.get());
        setUniformInt(this.pass1Uniforms, "uHeightFogAppliesUp", heightFogDirection.fogAppliesUp ? 1 : 0);
        setUniformInt(this.pass1Uniforms, "uHeightFogAppliesDown", heightFogDirection.fogAppliesDown ? 1 : 0);
        setUniformInt(this.pass1Uniforms, "uUseSphericalFog", useSphericalFog ? 1 : 0);
        setUniformInt(this.pass1Uniforms, "uHeightFogMixingMode", heightFogMixingMode.value);
        setUniformFloat(this.pass1Uniforms, "uCameraBlockYPos", (float) MC_RENDER.getCameraExactPosition().y);

        // Bind depth texture
        VTextureSelector.bindTexture(FOG_DEPTH_TEXTURE_SLOT, dhDepthTexture);

        // Save and set state
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

        // Render pass 1: compute fog into intermediate RGBA16F texture
        Renderer.getInstance().beginRenderPass(this.fogRenderPass, this.fogFramebuffer);
        Renderer.getInstance().bindGraphicsPipeline(this.fogComputePipeline);
        Renderer.getInstance().uploadAndBindUBOs(this.fogComputePipeline);
        Renderer.getDrawer().drawIndexed(this.quadVertexBuffer, this.quadIndexBuffer, 6);
        Renderer.getInstance().endRenderPass();

        // ====================== //
        // Pass 2: Apply Fog //
        // ====================== //

        setUniformVec2(this.pass2Uniforms, "gViewSize", this.width, this.height);

        // Bind fog texture + depth
        VTextureSelector.bindTexture(FOG_COLOR_TEXTURE_SLOT, this.fogFramebuffer.getColorAttachment());
        VTextureSelector.bindTexture(FOG_APPLY_DEPTH_TEXTURE_SLOT, dhDepthTexture);

        // SRC_ALPHA / ONE_MINUS_SRC_ALPHA blend (fog blends with alpha)
        PipelineState.blendInfo.enabled = true;
        PipelineState.blendInfo.srcRgbFactor = 6; // VK_BLEND_FACTOR_SRC_ALPHA
        PipelineState.blendInfo.dstRgbFactor = 7; // VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
        PipelineState.blendInfo.srcAlphaFactor = 1; // VK_BLEND_FACTOR_ONE
        PipelineState.blendInfo.dstAlphaFactor = 7; // VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
        PipelineState.blendInfo.blendOp = 0; // VK_BLEND_OP_ADD

        // Cached apply render pass
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
        Renderer.getInstance().bindGraphicsPipeline(this.fogApplyPipeline);
        Renderer.getInstance().uploadAndBindUBOs(this.fogApplyPipeline);
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

        if (newWidth == 0 || newHeight == 0)
            return;
        if (newWidth == this.width && newHeight == this.height)
            return;

        if (this.fogRenderPass != null)
            this.fogRenderPass.cleanUp();
        if (this.fogFramebuffer != null)
            this.fogFramebuffer.cleanUp();
        if (this.applyRenderPass != null) {
            this.applyRenderPass.cleanUp();
            this.applyRenderPass = null;
        }

        this.width = newWidth;
        this.height = newHeight;
        createFogFramebuffer();
    }

    // ========== //
    // Cleanup //
    // ========== //

    /**
     * Returns the intermediate fog texture for debug visualization. May be null.
     */
    public VulkanImage getIntermediateTexture() {
        return this.fogFramebuffer != null ? this.fogFramebuffer.getColorAttachment() : null;
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
        if (this.fogComputePipeline != null) {
            this.fogComputePipeline.cleanUp();
            this.fogComputePipeline = null;
        }
        if (this.fogApplyPipeline != null) {
            this.fogApplyPipeline.cleanUp();
            this.fogApplyPipeline = null;
        }
        if (this.fogRenderPass != null) {
            this.fogRenderPass.cleanUp();
            this.fogRenderPass = null;
        }
        if (this.applyRenderPass != null) {
            this.applyRenderPass.cleanUp();
            this.applyRenderPass = null;
        }
        if (this.fogFramebuffer != null) {
            this.fogFramebuffer.cleanUp();
            this.fogFramebuffer = null;
        }

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

    private void setUniformVec4(Map<String, MappedBuffer> uniforms, String name,
            float x, float y, float z, float w) {
        MappedBuffer mb = uniforms.get(name);
        if (mb != null) {
            mb.putFloat(0, x);
            mb.putFloat(4, y);
            mb.putFloat(8, z);
            mb.putFloat(12, w);
        }
    }

    // ================ //
    // Shader Loading //
    // ================ //

    private static String readShaderResource(String path) {
        try (InputStream is = DhFogPipeline.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null)
                throw new RuntimeException("[DH-Vulkan] Shader not found: " + path);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new RuntimeException("[DH-Vulkan] Failed to read shader: " + path, e);
        }
    }
}
