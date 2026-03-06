/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    VulkanMod integration for native Vulkan rendering backend.
 */

package com.seibel.distanthorizons.fabric.vulkan;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.glObject.GLProxy;
import com.seibel.distanthorizons.core.util.math.Mat4f;
import com.seibel.distanthorizons.core.util.math.Vec3f;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.VertexBuffer;
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
        return GLProxy.isVulkanModActive();
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
            LOGGER.info("[DH-Vulkan] VulkanRenderContext initialized successfully.");
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
        // VertexFormatElement(id, index, type, usage, count)
        VertexFormatElement position = new VertexFormatElement(0, 0,
                VertexFormatElement.Type.SHORT, VertexFormatElement.Usage.POSITION, 4);
        VertexFormatElement color = new VertexFormatElement(1, 0,
                VertexFormatElement.Type.UBYTE, VertexFormatElement.Usage.COLOR, 4);
        VertexFormatElement material = new VertexFormatElement(2, 0,
                VertexFormatElement.Type.INT, VertexFormatElement.Usage.GENERIC, 1);

        DH_TERRAIN_FORMAT = VertexFormat.builder()
                .add("Position", position)
                .add("Color", color)
                .add("Material", material)
                .build();
    }

    // ======================//
    // pipeline management //
    // ======================//

    /**
     * Creates the terrain rendering pipeline from DH's GLSL shaders.
     * Compiles standard.vert and flat_shaded.frag to SPIR-V.
     * Sets up persistent MappedBuffer uniforms with custom suppliers.
     */
    private GraphicsPipeline createTerrainPipeline() {
        LOGGER.info("[DH-Vulkan] Creating terrain pipeline...");

        String vertSource = readShaderResource("shaders/standard.vert");
        String fragSource = readShaderResource("shaders/flat_shaded.frag");

        vertSource = convertGlslForVulkan(vertSource, true);
        fragSource = convertGlslForVulkan(fragSource, false);

        Pipeline.Builder builder = new Pipeline.Builder(DH_TERRAIN_FORMAT);
        builder.compileShaders("dh_terrain", vertSource, fragSource);

        // UBOs and samplers
        List<UBO> ubos = new ArrayList<>();
        List<ImageDescriptor> imageDescriptors = new ArrayList<>();

        // Binding 0: Main uniforms UBO
        AlignedStruct.Builder uboBuilder = new AlignedStruct.Builder();

        // Create persistent MappedBuffer for each uniform and set as supplier
        addDhUniform(uboBuilder, "matrix4x4", "uCombinedMatrix", 1, 64); // mat4 = 16 floats = 64 bytes
        addDhUniform(uboBuilder, "float", "uModelOffset", 3, 12); // vec3 = 3 floats = 12 bytes
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

        UBO mainUbo = uboBuilder.buildUBO(0, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
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
        Uniform.Info info = Uniform.createUniformInfo(type, name, count);

        // Create a persistent MappedBuffer for this uniform
        MappedBuffer mb = new MappedBuffer(byteSize);
        this.uniformBuffers.put(name, mb);

        // Set the supplier — the Uniform will read from this buffer on UBO.update()
        info.setBufferSupplier(() -> mb);

        builder.addUniformInfo(info);
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
        Renderer.getInstance().uploadAndBindUBOs(this.terrainPipeline);
    }

    public void drawIndexed(Buffer vertexBuffer, IndexBuffer indexBuffer, int indexCount) {
        Renderer.getInstance().getDrawer().drawIndexed(vertexBuffer, indexBuffer, indexCount);
    }

    public GraphicsPipeline getTerrainPipeline() {
        return this.terrainPipeline;
    }

    // ======================//
    // buffer management //
    // ======================//

    public static VertexBuffer createVertexBuffer(int sizeBytes) {
        return new VertexBuffer(sizeBytes, MemoryTypes.HOST_MEM);
    }

    public static IndexBuffer createIndexBuffer(int sizeBytes) {
        return new IndexBuffer(sizeBytes, MemoryTypes.HOST_MEM, IndexBuffer.IndexType.UINT32);
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

    /**
     * Convert DH's GLSL 1.50 shaders to GLSL 4.50 for Vulkan/SPIR-V compilation.
     * Key changes:
     * - Version 150 → 450
     * - Add layout(location=N) qualifiers to inputs/outputs
     * - Convert individual uniforms to a UBO block
     * - Convert sampler to descriptor set binding
     * - Change uvec4 vPosition → ivec4 (matches VK_FORMAT_R16G16B16A16_SINT)
     * and add uvec4 cast for unsigned bitwise operations
     */
    static String convertGlslForVulkan(String source, boolean isVertex) {
        source = source.replace("#version 150 core", "#version 450");
        source = source.replace("#version 150", "#version 450");

        if (isVertex) {
            // Change uvec4 → ivec4 to match VK_FORMAT_R16G16B16A16_SINT from vertex format
            source = source.replaceFirst("in uvec4 vPosition;",
                    "layout(location = 0) in ivec4 vPosition;");
            source = source.replaceFirst("in vec4 color;",
                    "layout(location = 1) in vec4 color;");

            source = source.replaceFirst("out vec4 vPos;", "layout(location = 0) out vec4 vPos;");
            source = source.replaceFirst("out vec4 vertexColor;", "layout(location = 1) out vec4 vertexColor;");
            source = source.replaceFirst("out vec3 vertexWorldPos;", "layout(location = 2) out vec3 vertexWorldPos;");
            source = source.replaceFirst("out float vertexYPos;", "layout(location = 3) out float vertexYPos;");

            // In main(), add a uvec4 conversion and fix type casts:
            // ivec4 → vec4 for vPos, ivec4 → vec3/float for worldPos/yPos
            source = source.replace("vPos = vPosition;",
                    "uvec4 uVPosition = uvec4(vPosition);\n    vPos = vec4(vPosition);");
            source = source.replace("vertexWorldPos = vPosition.xyz", "vertexWorldPos = vec3(vPosition.xyz)");
            source = source.replace("vertexYPos = vPosition.y", "vertexYPos = float(vPosition.y)");
            source = source.replace("uint meta = vPosition.a;", "uint meta = uVPosition.a;");
        } else {
            source = source.replaceFirst("in vec4 vPos;", "layout(location = 0) in vec4 vPos;");
            source = source.replaceFirst("in vec4 vertexColor;", "layout(location = 1) in vec4 vertexColor;");
            source = source.replaceFirst("in vec3 vertexWorldPos;", "layout(location = 2) in vec3 vertexWorldPos;");

            // gl_FragCoord is a built-in in GLSL 4.50; remove the redeclaration
            source = source.replace("in vec4 gl_FragCoord;", "");

            source = source.replaceFirst("out vec4 fragColor;", "layout(location = 0) out vec4 fragColor;");
        }

        // Build UBO block from individual uniforms
        StringBuilder uboBlock = new StringBuilder();
        uboBlock.append("layout(set = 0, binding = 0) uniform DhUniforms {\n");

        String[] uniformNames = {
                "uCombinedMatrix", "uModelOffset", "uWorldYOffset",
                "uMircoOffset", "uEarthRadius", "uIsWhiteWorld",
                "uClipDistance", "uDitherDhRendering",
                "uNoiseEnabled", "uNoiseSteps", "uNoiseIntensity", "uNoiseDropoff"
        };

        for (String name : uniformNames) {
            // Match "uniform TYPE name" optionally with "= defaultValue"
            String regex = "uniform\\s+(\\w+)\\s+" + name + "\\s*(=\\s*[^;]+)?;";
            Matcher matcher = Pattern.compile(regex).matcher(source);
            if (matcher.find()) {
                String type = matcher.group(1);
                uboBlock.append("    ").append(type).append(" ").append(name).append(";\n");
                source = source.replaceFirst(regex, "// moved to UBO: " + name);
            }
        }
        uboBlock.append("};\n\n");

        // Sampler binding
        source = source.replace("uniform sampler2D uLightMap;",
                "layout(set = 0, binding = 1) uniform sampler2D uLightMap;");

        // Insert UBO block after version declaration
        int versionEnd = source.indexOf('\n') + 1;
        source = source.substring(0, versionEnd) + "\n" + uboBlock + source.substring(versionEnd);

        // Debug: log the converted shader
        LOGGER.info("[DH-Vulkan] Converted {} shader:\n{}", isVertex ? "VERTEX" : "FRAGMENT", source);

        return source;
    }
}
