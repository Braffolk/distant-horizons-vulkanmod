/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Reads MC's swapchain depth into a sampleable R32F color texture.
 *    Required because on NVIDIA Windows the swapchain depth can't be
 *    sampled while it's an active render pass attachment, and lacks
 *    TRANSFER_SRC for vkCmdCopyImage.
 */

package com.braffolk.dhvulkan;

import com.braffolk.dhvulkan.compat.Compat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

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
 * Minimal pipeline that reads MC's swapchain depth and writes it as a
 * regular R32F color texture, avoiding the need to sample depth while
 * it's an active render pass attachment.
 * <p>
 * Must be called AFTER Renderer.endRenderPass() (so MC depth is not
 * an active attachment) and BEFORE Compat.rebindMainTarget().
 */
public class DhDepthReaderPipeline {

    private static final int VK_SHADER_STAGE_VERTEX_BIT = 0x00000001;
    private static final int VK_SHADER_STAGE_FRAGMENT_BIT = 0x00000010;
    private static final int VK_FORMAT_R32_SFLOAT = 100;
    private static final int VK_ATTACHMENT_LOAD_OP_DONT_CARE = 2;
    private static final int VK_ATTACHMENT_STORE_OP_STORE = 0;
    private static final int VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL = 5;

    // Reuse composite's DH color slot (3) — not active during depth reader's pass
    private static final int MC_DEPTH_READ_SLOT = 3;

    private static final VertexFormat QUAD_FORMAT;
    static {
        VertexFormatElement position = Compat.vertexFormatElement(0, 0,
                VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.POSITION, 2);
        QUAD_FORMAT = Compat.buildVertexFormat(
                new String[] { "Position" },
                new VertexFormatElement[] { position });
    }

    private GraphicsPipeline pipeline;
    private Framebuffer framebuffer;
    private RenderPass renderPass;
    private Object quadVertexBuffer;
    private int width, height;
    private boolean initialized = false;

    private static final com.seibel.distanthorizons.core.logging.DhLogger LOGGER = new com.seibel.distanthorizons.core.logging.DhLoggerBuilder()
            .build();

    public void init(int width, int height) {
        if (this.initialized)
            return;
        this.width = width;
        this.height = height;
        createQuadBuffers();
        createFramebuffer();
        createPipeline();
        Renderer.getInstance().addOnResizeCallback(this::onResize);
        this.initialized = true;
        LOGGER.info("[DH-Vulkan] DhDepthReaderPipeline initialized ({}x{})", width, height);
    }

    private void createQuadBuffers() {
        ByteBuffer vertexData = ByteBuffer.allocateDirect(24);
        vertexData.order(ByteOrder.nativeOrder());
        for (int i = 0; i < 6; i++)
            vertexData.putFloat(0);
        vertexData.flip();
        this.quadVertexBuffer = Compat.createGpuVertexBuffer(vertexData.remaining());
        Compat.copyBuffer(this.quadVertexBuffer, vertexData, vertexData.remaining());
    }

    private void createFramebuffer() {
        this.framebuffer = new Framebuffer.Builder(this.width, this.height, 1, false)
                .setFormat(VK_FORMAT_R32_SFLOAT)
                .setLinearFiltering(false)
                .build();

        RenderPass.Builder rpBuilder = RenderPass.builder(this.framebuffer);
        rpBuilder.getColorAttachmentInfo()
                .setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE)
                .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        this.renderPass = rpBuilder.build();
    }

    private void createPipeline() {
        String vertSource = readShaderResource("shaders/vulkan/dh_apply.vert");
        String fragSource = readShaderResource("shaders/vulkan/dh_depth_read.frag");

        Pipeline.Builder builder = new Pipeline.Builder(QUAD_FORMAT);
        builder.compileShaders("dh_depth_read", vertSource, fragSource);

        List<UBO> ubos = new ArrayList<>();
        AlignedStruct.Builder uboBuilder = new AlignedStruct.Builder();
        // VulkanMod requires non-empty UBO — add dummy uniform
        net.vulkanmod.vulkan.util.MappedBuffer dummyBuf = new net.vulkanmod.vulkan.util.MappedBuffer(4);
        dummyBuf.putInt(0, 0);
        Compat.addUniformWithBuffer(uboBuilder, "int", "uDummy", 1, () -> dummyBuf);
        UBO mainUbo = uboBuilder.buildUBO(0, VK_SHADER_STAGE_FRAGMENT_BIT);
        // VM 0.4.2: must set suppliers after buildUBO (addUniformInfo doesn't wire
        // them)
        java.util.Map<String, net.vulkanmod.vulkan.util.MappedBuffer> bufMap = new java.util.HashMap<>();
        bufMap.put("uDummy", dummyBuf);
        Compat.setUniformSuppliers(mainUbo, bufMap);
        ubos.add(mainUbo);

        List<ImageDescriptor> imageDescriptors = new ArrayList<>();
        imageDescriptors.add(new ImageDescriptor(1, "sampler2D", "gMcDepthTexture", MC_DEPTH_READ_SLOT));
        builder.setUniforms(ubos, imageDescriptors);

        this.pipeline = builder.createGraphicsPipeline();
    }

    /**
     * Read MC's depth into the R32F texture. Must be called while NO render
     * pass is active (after endRenderPass, before rebindMainTarget).
     * Returns the R32F color attachment containing MC depth values.
     */
    public VulkanImage readDepth(VulkanImage mcDepth) {
        if (!this.initialized)
            return null;

        // Prepare depth-only view (does NOT restore — stays set through upload)
        Compat.prepareMcDepthForSampling(MC_DEPTH_READ_SLOT, mcDepth);

        // Save/set state
        boolean prevCull = VRenderSystem.cull;
        boolean prevBlend = PipelineState.blendInfo.enabled;
        VRenderSystem.cull = false;
        PipelineState.blendInfo.enabled = false;

        // Start our own render pass (MC depth is NOT an active attachment here)
        Compat.beginRenderPass(this.renderPass, this.framebuffer);
        Renderer.getInstance().bindGraphicsPipeline(this.pipeline);
        Renderer.getInstance().uploadAndBindUBOs(this.pipeline);
        Compat.draw(this.quadVertexBuffer, 3);
        Renderer.getInstance().endRenderPass();

        // NOW restore the original view on the depth image
        Compat.restoreMcDepthView();

        // Restore state
        VRenderSystem.cull = prevCull;
        PipelineState.blendInfo.enabled = prevBlend;

        return this.framebuffer.getColorAttachment();
    }

    private void onResize() {
        // Framebuffer auto-resizes via VulkanMod
    }

    public void cleanup() {
        // VulkanMod manages pipeline/framebuffer lifecycle
    }

    private String readShaderResource(String path) {
        try {
            InputStream is = DhDepthReaderPipeline.class.getClassLoader()
                    .getResourceAsStream(path);
            if (is == null)
                throw new RuntimeException("Shader not found: " + path);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read shader: " + path, e);
        }
    }
}
