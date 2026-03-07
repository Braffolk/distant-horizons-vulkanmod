/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Composite pipeline for Phase 6 — composites DH's framebuffer onto MC's.
 */

package com.seibel.distanthorizons.fabric.vulkan;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
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

    private GraphicsPipeline compositePipeline;
    private VertexBuffer quadVertexBuffer;
    private IndexBuffer quadIndexBuffer;
    private boolean initialized = false;

    /**
     * Simple vertex format for the fullscreen quad: just vec2 position.
     */
    private static final VertexFormat QUAD_FORMAT;
    static {
        VertexFormatElement position = new VertexFormatElement(0, 0,
                VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.POSITION, 2);
        QUAD_FORMAT = VertexFormat.builder()
                .add("Position", position)
                .build();
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
     * Creates a fullscreen quad: 4 vertices at NDC corners, 6 indices (2
     * triangles).
     */
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

    /**
     * Creates the composite graphics pipeline:
     * - Vertex shader: fullscreen quad
     * - Fragment shader: samples DH color + depth, writes both
     * - No UBO uniforms needed (just samplers)
     */
    private void createPipeline() {
        String vertSource = readShaderResource("shaders/vulkan/dh_apply.vert");
        String fragSource = readShaderResource("shaders/vulkan/dh_apply.frag");

        Pipeline.Builder builder = new Pipeline.Builder(QUAD_FORMAT);
        builder.compileShaders("dh_composite", vertSource, fragSource);

        // UBOs — we need an empty UBO at binding 0 because VulkanMod expects
        // at least one UBO in the descriptor set layout. We create a minimal
        // dummy UBO with a single unused float.
        List<UBO> ubos = new ArrayList<>();
        AlignedStruct.Builder uboBuilder = new AlignedStruct.Builder();
        Uniform.Info dummyInfo = Uniform.createUniformInfo("float", "_unused", 1);
        MappedBuffer dummyBuf = new MappedBuffer(4);
        dummyBuf.putFloat(0, 0.0f);
        dummyInfo.setBufferSupplier(() -> dummyBuf);
        uboBuilder.addUniformInfo(dummyInfo);
        UBO mainUbo = uboBuilder.buildUBO(0, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        ubos.add(mainUbo);

        // Image descriptors — DH color at binding 1, DH depth at binding 2
        List<ImageDescriptor> imageDescriptors = new ArrayList<>();
        imageDescriptors.add(new ImageDescriptor(1, "sampler2D", "gDhColorTexture", DH_COLOR_TEXTURE_SLOT));
        imageDescriptors.add(new ImageDescriptor(2, "sampler2D", "gDhDepthTexture", DH_DEPTH_TEXTURE_SLOT));

        builder.setUniforms(ubos, imageDescriptors);
        this.compositePipeline = builder.createGraphicsPipeline();
    }

    /**
     * Draws the fullscreen composite quad. Must be called while MC's render pass
     * is active and after binding DH's framebuffer textures to the
     * VTextureSelector slots.
     */
    public void render(VulkanImage dhColorTexture, VulkanImage dhDepthTexture) {
        if (!this.initialized) {
            return;
        }

        // Bind DH framebuffer textures to the expected slots
        VTextureSelector.bindTexture(DH_COLOR_TEXTURE_SLOT, dhColorTexture);
        VTextureSelector.bindTexture(DH_DEPTH_TEXTURE_SLOT, dhDepthTexture);

        // Set pipeline state for composite: no blend, no cull, depth write always
        boolean prevCull = VRenderSystem.cull;
        boolean prevDepthMask = VRenderSystem.depthMask;
        int prevDepthFun = VRenderSystem.depthFun;
        boolean prevBlend = PipelineState.blendInfo.enabled;

        VRenderSystem.cull = false;
        VRenderSystem.depthMask = true;
        VRenderSystem.depthFun = 515; // GL_LEQUAL — only write where DH depth ≤ MC depth
        PipelineState.blendInfo.enabled = false;

        // Bind pipeline and draw
        Renderer.getInstance().bindGraphicsPipeline(this.compositePipeline);
        Renderer.getInstance().uploadAndBindUBOs(this.compositePipeline);
        Renderer.getDrawer().drawIndexed(this.quadVertexBuffer, this.quadIndexBuffer, 6);

        // Restore state
        VRenderSystem.cull = prevCull;
        VRenderSystem.depthMask = prevDepthMask;
        VRenderSystem.depthFun = prevDepthFun;
        PipelineState.blendInfo.enabled = prevBlend;
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
