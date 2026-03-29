package com.braffolk.dhvulkan.core.pipeline;

import com.braffolk.dhvulkan.compat.Compat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Shadow pipeline for DH LODs in Beryl's shadow pass.
 * <p>
 * Simple depth-only pipeline that transforms DH vertices by the light-space
 * matrix and writes depth into Beryl's shadow framebuffer. Uses DH's standard
 * 16-byte vertex format but a minimal shadow shader.
 */
public class DhShadowPipeline {
    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanShadow");

    private static final int VK_SHADER_STAGE_VERTEX_BIT = 0x00000001;
    private static final int VK_SHADER_STAGE_FRAGMENT_BIT = 0x00000010;

    private GraphicsPipeline shadowPipeline;
    private boolean initialized = false;

    // Uniform buffers
    private MappedBuffer lightSpaceMatrixBuf;
    private MappedBuffer modelOffsetBuf;
    private MappedBuffer worldYOffsetBuf;

    /**
     * DH's 16-byte vertex format (same as VulkanRenderContext).
     */
    private static final VertexFormat DH_TERRAIN_FORMAT;
    static {
        VertexFormatElement position = Compat.vertexFormatElement(0, 0,
                VertexFormatElement.Type.SHORT, VertexFormatElement.Usage.POSITION, 4);
        VertexFormatElement color = Compat.vertexFormatElement(1, 0,
                VertexFormatElement.Type.UBYTE, VertexFormatElement.Usage.COLOR, 4);
        VertexFormatElement material = Compat.vertexFormatElement(2, 0,
                VertexFormatElement.Type.INT, VertexFormatElement.Usage.GENERIC, 1);

        DH_TERRAIN_FORMAT = Compat.buildVertexFormat(
                new String[] { "Position", "Color", "Material" },
                new VertexFormatElement[] { position, color, material });
    }

    public void init() {
        if (this.initialized) return;

        try {
            createPipeline();
            this.initialized = true;
            LOGGER.info("[DH-Vulkan] DhShadowPipeline initialized.");
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] DhShadowPipeline init failed", e);
        }
    }

    private void createPipeline() {
        String vertSource = readShaderResource("shaders/vulkan/dh_shadow.vert");
        String fragSource = readShaderResource("shaders/vulkan/dh_shadow.frag");

        Pipeline.Builder builder = new Pipeline.Builder(DH_TERRAIN_FORMAT);
        builder.compileShaders("dh_shadow", vertSource, fragSource);

        List<UBO> ubos = new ArrayList<>();
        List<ImageDescriptor> imageDescriptors = new ArrayList<>();

        AlignedStruct.Builder uboBuilder = new AlignedStruct.Builder();

        // mat4 uLightSpaceMatrix — 64 bytes
        this.lightSpaceMatrixBuf = new MappedBuffer(64);
        Compat.addUniformWithBuffer(uboBuilder, "matrix4x4", "uLightSpaceMatrix", 1,
                () -> this.lightSpaceMatrixBuf);

        // vec3 uModelOffset — 12 bytes (std140 pads to 16)
        this.modelOffsetBuf = new MappedBuffer(12);
        Compat.addUniformWithBuffer(uboBuilder, "float", "uModelOffset", 3,
                () -> this.modelOffsetBuf);

        // float uWorldYOffset — 4 bytes
        this.worldYOffsetBuf = new MappedBuffer(4);
        Compat.addUniformWithBuffer(uboBuilder, "float", "uWorldYOffset", 1,
                () -> this.worldYOffsetBuf);

        UBO mainUbo = uboBuilder.buildUBO(0, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        Compat.setUniformSuppliers(mainUbo, java.util.Map.of(
                "uLightSpaceMatrix", this.lightSpaceMatrixBuf,
                "uModelOffset", this.modelOffsetBuf,
                "uWorldYOffset", this.worldYOffsetBuf));
        ubos.add(mainUbo);

        builder.setUniforms(ubos, imageDescriptors);
        this.shadowPipeline = builder.createGraphicsPipeline();
    }

    /**
     * Set the light-space matrix from Beryl's RenderingPipeline.lightSpaceMatrix.
     */
    public void setLightSpaceMatrix(java.nio.ByteBuffer matrixBuffer) {
        if (!this.initialized) return;
        for (int i = 0; i < 16; i++) {
            this.lightSpaceMatrixBuf.putFloat(i * 4, matrixBuffer.getFloat(i * 4));
        }
    }

    /**
     * Set the model offset for the current chunk section.
     */
    public void setModelOffset(float x, float y, float z) {
        if (!this.initialized) return;
        this.modelOffsetBuf.putFloat(0, x);
        this.modelOffsetBuf.putFloat(4, y);
        this.modelOffsetBuf.putFloat(8, z);
    }

    /**
     * Bind the shadow pipeline and upload UBOs.
     */
    public void bind() {
        if (!this.initialized) return;
        Renderer.getInstance().bindGraphicsPipeline(this.shadowPipeline);
        Renderer.getInstance().uploadAndBindUBOs(this.shadowPipeline);
    }

    /**
     * Upload UBOs after model offset change — needed per draw batch.
     */
    public void uploadUBOs() {
        if (!this.initialized) return;
        Renderer.getInstance().uploadAndBindUBOs(this.shadowPipeline);
    }

    public boolean isInitialized() {
        return this.initialized;
    }

    public void cleanup() {
        if (this.shadowPipeline != null) {
            this.shadowPipeline.cleanUp();
            this.shadowPipeline = null;
        }
        if (this.lightSpaceMatrixBuf != null) {
            org.lwjgl.system.MemoryUtil.memFree(this.lightSpaceMatrixBuf.buffer);
            this.lightSpaceMatrixBuf = null;
        }
        if (this.modelOffsetBuf != null) {
            org.lwjgl.system.MemoryUtil.memFree(this.modelOffsetBuf.buffer);
            this.modelOffsetBuf = null;
        }
        if (this.worldYOffsetBuf != null) {
            org.lwjgl.system.MemoryUtil.memFree(this.worldYOffsetBuf.buffer);
            this.worldYOffsetBuf = null;
        }
        this.initialized = false;
    }

    private static String readShaderResource(String path) {
        try (InputStream is = DhShadowPipeline.class.getClassLoader().getResourceAsStream(path)) {
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
