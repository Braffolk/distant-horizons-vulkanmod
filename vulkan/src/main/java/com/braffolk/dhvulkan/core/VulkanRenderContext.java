/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    VulkanMod integration for native Vulkan rendering backend.
 */

package com.braffolk.dhvulkan.core;

import com.braffolk.dhvulkan.compat.Compat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.glObject.GLProxy;
import com.seibel.distanthorizons.core.util.math.Mat4f;
import com.seibel.distanthorizons.core.util.math.Vec3f;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.texture.VTextureSelector;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Central bridge between Distant Horizons rendering and VulkanMod's native
 * Vulkan API.
 * <p>
 * Manages Vulkan pipeline creation (GLSL to SPIR-V compilation), uniform
 * data buffers, and draw call submission via VulkanMod's {@link Renderer} and
 * {@link Drawer}.
 */
public class VulkanRenderContext {
    private static final DhLogger LOGGER = new DhLoggerBuilder().build();

    private static final int VK_SHADER_STAGE_VERTEX_BIT = 0x00000001;
    private static final int VK_SHADER_STAGE_FRAGMENT_BIT = 0x00000010;

    private static VulkanRenderContext instance;

    /** The terrain rendering pipeline (replaces DhTerrainShaderProgram) */
    private GraphicsPipeline terrainPipeline;

    /** Whether the context has been initialized */
    private boolean initialized = false;

    /**
     * Persistent MappedBuffer objects for each DH uniform.
     * These are allocated once and reused every frame — the supplier returns
     * the same buffer, and we update its contents before each UBO upload.
     */
    private final Map<String, MappedBuffer> uniformBuffers = new HashMap<>();

    /**
     * Direct reference to the model offset buffer — avoids HashMap.get(String)
     * on the hottest uniform setter (~200× per frame per render pass).
     */
    private MappedBuffer modelOffsetBuffer;

    // =============//
    // constructor //
    // =============//

    private VulkanRenderContext() {
    }

    public static VulkanRenderContext getInstance() {
        if (instance == null) {
            instance = new VulkanRenderContext();
        }
        return instance;
    }

    public static boolean isActive() {
        return com.braffolk.dhvulkan.compat.Compat.isVulkanModActive();
    }

    // ================//
    // initialization //
    // ================//

    public void init() {
        if (this.initialized) {
            return;
        }

        LOGGER.info("[DH-Vulkan] Initializing VulkanRenderContext...");
        try {
            this.terrainPipeline = createTerrainPipeline();
            this.initialized = true;
            LOGGER.info("[DH-Vulkan] VulkanRenderContext initialized.");
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Failed to initialize VulkanRenderContext", e);
            throw new RuntimeException("DH Vulkan initialization failed", e);
        }
    }

    /**
     * DH's 16-byte vertex format:
     * Attr 0 (Position): SHORT×4 = 8 bytes (shader: uvec4 vPosition)
     * Attr 1 (Color): UBYTE×4 = 4 bytes (shader: vec4 color)
     * Attr 2 (Generic): INT×1 = 4 bytes (material/normal/padding)
     * Total stride: 16 bytes
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

    // ======================//
    // pipeline management //
    // ======================//

    /**
     * Creates the terrain rendering pipeline from native Vulkan GLSL 450 shaders.
     * These are self-contained — no conversion from DH's GL shaders needed.
     */
    private GraphicsPipeline createTerrainPipeline() {

        String vertSource = readShaderResource("shaders/vulkan/dh_terrain.vert");
        String fragSource = readShaderResource("shaders/vulkan/dh_terrain.frag");

        // On VM 0.6.1: enable push constants in shaders via preprocessor define.
        // Must be injected AFTER #version (GLSL requires #version as first directive).
        if (Compat.hasPushConstants()) {
            vertSource = vertSource.replaceFirst("(#version 450)", "$1\n#define USE_PUSH_CONSTANTS");
            fragSource = fragSource.replaceFirst("(#version 450)", "$1\n#define USE_PUSH_CONSTANTS");
        }
        
        // Enable Beryl Compat if active
        if (Compat.isBerylActive()) {
            fragSource = fragSource.replaceFirst("(#version 450)", "$1\n#define BERYL_COMPAT");
        }

        Pipeline.Builder builder = new Pipeline.Builder(DH_TERRAIN_FORMAT);
        builder.compileShaders("dh_terrain", vertSource, fragSource);

        // UBOs and samplers
        List<UBO> ubos = new ArrayList<>();
        List<ImageDescriptor> imageDescriptors = new ArrayList<>();

        // Binding 0: Main uniforms UBO
        AlignedStruct.Builder uboBuilder = new AlignedStruct.Builder();

        // Create persistent MappedBuffer for each uniform and set as supplier
        addDhUniform(uboBuilder, "matrix4x4", "uCombinedMatrix", 1, 64); // mat4 = 16 floats = 64 bytes

        // uModelOffset: push constant on VM 0.6.1, UBO on VM 0.4.2
        this.modelOffsetBuffer = new MappedBuffer(12); // vec3 = 3 floats = 12 bytes
        if (Compat.hasPushConstants()) {
            // Model offset as push constant — zero descriptor overhead per draw
            Compat.buildAndSetPushConstants(builder, "float", "uModelOffset", 3,
                    () -> this.modelOffsetBuffer);
        } else {
            // Fallback: model offset in UBO (requires full rebind per draw)
            this.uniformBuffers.put("uModelOffset", this.modelOffsetBuffer);
            Compat.addUniformWithBuffer(uboBuilder, "float", "uModelOffset", 3,
                    () -> this.modelOffsetBuffer);
        }

        addDhUniform(uboBuilder, "float", "uWorldYOffset", 1, 4);
        addDhUniform(uboBuilder, "float", "uMircoOffset", 1, 4);
        addDhUniform(uboBuilder, "float", "uEarthRadius", 1, 4);
        addDhUniform(uboBuilder, "int", "uIsWhiteWorld", 1, 4);
        addDhUniform(uboBuilder, "float", "uClipDistance", 1, 4);
        addDhUniform(uboBuilder, "int", "uDitherDhRendering", 1, 4);
        addDhUniform(uboBuilder, "int", "uNoiseEnabled", 1, 4);
        addDhUniform(uboBuilder, "int", "uNoiseSteps", 1, 4);
        addDhUniform(uboBuilder, "float", "uNoiseIntensity", 1, 4);
        addDhUniform(uboBuilder, "int", "uNoiseDropoff", 1, 4);
        
        // Beryl Compat uniforms
        // We always define them in the VBO for safety. Beryl sets them via reflection.
        addDhUniform(uboBuilder, "matrix4x4", "uInvViewMatrix", 1, 64);
        addDhUniform(uboBuilder, "float", "uBerylFogFactor", 1, 4);
        addDhUniform(uboBuilder, "float", "uBerylFogFactorRaw", 1, 4);
        addDhUniform(uboBuilder, "float", "uBerylFogEnd", 1, 4);
        addDhUniform(uboBuilder, "float", "uBerylFogStart", 1, 4);
        addDhUniform(uboBuilder, "float", "uBerylLightIntensity", 1, 4);
        addDhUniform(uboBuilder, "float", "uBerylNightMultiplier", 1, 4);
        addDhUniform(uboBuilder, "float", "uBerylLightVisibility", 1, 4);
        addDhUniform(uboBuilder, "float", "uBerylMinAmbientLight", 1, 4);
        addDhUniform(uboBuilder, "float", "uBerylAmbientLightFactor", 1, 4);
        addDhUniform(uboBuilder, "float", "uBerylLightDir", 3, 16); // std140 vec3
        addDhUniform(uboBuilder, "float", "uBerylFogColor", 3, 16);
        addDhUniform(uboBuilder, "float", "uBerylLightColor", 3, 16);
        addDhUniform(uboBuilder, "float", "uBerylSkyColor", 3, 16);
        addDhUniform(uboBuilder, "float", "uBerylUpVector", 3, 16);

        UBO mainUbo = uboBuilder.buildUBO(0, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        // VM 0.4.2: uniforms' setSupplier() auto-lookup only finds MC's built-in
        // uniforms (ModelViewMat etc). Set suppliers for our custom DH uniforms.
        // No-op on VM 0.6.1 where Info.setBufferSupplier() handles it.
        Compat.setUniformSuppliers(mainUbo, this.uniformBuffers);
        ubos.add(mainUbo);

        // Binding 1: LightMap sampler
        // VulkanMod hardcodes lightmap at texture slot 2 (see
        // VTextureSelector.setLightTexture())
        imageDescriptors.add(new ImageDescriptor(1, "sampler2D", "uLightMap", 2));

        builder.setUniforms(ubos, imageDescriptors);

        return builder.createGraphicsPipeline();
    }

    /**
     * Creates a persistent MappedBuffer for a DH uniform and registers it as a
     * supplier on the Uniform.Info. This avoids allocating a new buffer every
     * frame.
     */
    private void addDhUniform(AlignedStruct.Builder builder, String type, String name, int count, int byteSize) {
        MappedBuffer mb = new MappedBuffer(byteSize);
        this.uniformBuffers.put(name, mb);

        // Cache direct reference for the hot-path model offset uniform
        if ("uModelOffset".equals(name)) {
            this.modelOffsetBuffer = mb;
        }

        Compat.addUniformWithBuffer(builder, type, name, count, () -> mb);
    }

    // ===================//
    // uniform updates //
    // ===================//

    /** Write a mat4 uniform value (column-major for std140 layout) */
    public void setUniformMat4(String name, Mat4f matrix) {
        MappedBuffer mb = this.uniformBuffers.get(name);
        if (mb == null)
            return;

        // Column 0
        mb.putFloat(0, matrix.m00);
        mb.putFloat(4, matrix.m10);
        mb.putFloat(8, matrix.m20);
        mb.putFloat(12, matrix.m30);
        // Column 1
        mb.putFloat(16, matrix.m01);
        mb.putFloat(20, matrix.m11);
        mb.putFloat(24, matrix.m21);
        mb.putFloat(28, matrix.m31);
        // Column 2
        mb.putFloat(32, matrix.m02);
        mb.putFloat(36, matrix.m12);
        mb.putFloat(40, matrix.m22);
        mb.putFloat(44, matrix.m32);
        // Column 3
        mb.putFloat(48, matrix.m03);
        mb.putFloat(52, matrix.m13);
        mb.putFloat(56, matrix.m23);
        mb.putFloat(60, matrix.m33);
    }

    /** Write a vec3 uniform value */
    public void setUniformVec3f(String name, Vec3f value) {
        MappedBuffer mb = this.uniformBuffers.get(name);
        if (mb == null)
            return;

        mb.putFloat(0, value.x);
        mb.putFloat(4, value.y);
        mb.putFloat(8, value.z);
    }

    /**
     * Write model offset directly (hot path, ~200× per frame).
     * Uses direct field reference — no HashMap lookup.
     */
    public void setModelOffset(Vec3f value) {
        this.modelOffsetBuffer.putFloat(0, value.x);
        this.modelOffsetBuffer.putFloat(4, value.y);
        this.modelOffsetBuffer.putFloat(8, value.z);
    }

    /**
     * Apply per-draw state before each drawIndexed call.
     * - VM 0.6.1: issues vkCmdPushConstants (12 bytes to cmd buffer, no alloc)
     * - VM 0.4.2: full uploadAndBindUBOs (descriptor set alloc + UBO copy)
     */
    public void applyPerDrawState() {
        Compat.applyPerDrawState(this.terrainPipeline);
    }

    /** Write a float uniform value */
    public void setUniformFloat(String name, float value) {
        MappedBuffer mb = this.uniformBuffers.get(name);
        if (mb == null)
            return;

        mb.putFloat(0, value);
    }

    /** Write an int uniform value */
    public void setUniformInt(String name, int value) {
        MappedBuffer mb = this.uniformBuffers.get(name);
        if (mb == null)
            return;

        mb.putInt(0, value);
    }

    /** Write a boolean uniform value (stored as int) */
    public void setUniformBool(String name, boolean value) {
        setUniformInt(name, value ? 1 : 0);
    }

    // ===========//
    // rendering //
    // ===========//

    public void bindTerrainPipeline() {
        if (!this.initialized) {
            this.init();
        }
        Renderer.getInstance().bindGraphicsPipeline(this.terrainPipeline);
    }

    /**
     * Upload UBO data and bind descriptor sets.
     * Must be called after bindTerrainPipeline() and after updating uniforms,
     * before any draw calls.
     */
    public void uploadAndBindUBOs() {
        if (Compat.isBerylActive()) {
            Compat.updateBerylCompatUniforms(this.uniformBuffers, 1.0f);
        }
        Renderer.getInstance().uploadAndBindUBOs(this.terrainPipeline);
    }

    public void drawIndexed(Object vertexBuffer, Object indexBuffer, int indexCount) {
        Compat.drawIndexed(vertexBuffer, indexBuffer, indexCount);
    }

    public GraphicsPipeline getTerrainPipeline() {
        return this.terrainPipeline;
    }

    // ======================//
    // buffer management //
    // ======================//

    public static Object createVertexBuffer(int sizeBytes) {
        return Compat.createVertexBuffer(sizeBytes);
    }

    public static Object createIndexBuffer(int sizeBytes) {
        return Compat.createIndexBuffer(sizeBytes);
    }

    // =========//
    // cleanup //
    // =========//

    public void cleanup() {
        if (this.terrainPipeline != null) {
            this.terrainPipeline.cleanUp();
            this.terrainPipeline = null;
        }
        // Free MappedBuffers
        for (MappedBuffer mb : this.uniformBuffers.values()) {
            MemoryUtil.memFree(mb.buffer);
        }
        this.uniformBuffers.clear();
        this.initialized = false;
        LOGGER.info("[DH-Vulkan] VulkanRenderContext cleaned up.");
    }

    // ================//
    // shader helpers //
    // ================//

    private static String readShaderResource(String path) {
        try (InputStream is = VulkanRenderContext.class.getClassLoader().getResourceAsStream(path)) {
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
