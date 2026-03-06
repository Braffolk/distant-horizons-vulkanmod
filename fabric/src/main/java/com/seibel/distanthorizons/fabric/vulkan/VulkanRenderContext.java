/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    VulkanMod integration for native Vulkan rendering backend.
 */

package com.seibel.distanthorizons.fabric.vulkan;

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.glObject.GLProxy;
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Central bridge between Distant Horizons rendering and VulkanMod's native
 * Vulkan API.
 * <p>
 * Manages Vulkan pipeline creation (GLSL to SPIR-V compilation), vertex/index
 * buffer
 * allocation, and draw call submission via VulkanMod's {@link Renderer} and
 * {@link Drawer}.
 * <p>
 * This class lives in the {@code fabric} module because VulkanMod classes are
 * only
 * available on Fabric's compile classpath.
 */
public class VulkanRenderContext {
    private static final DhLogger LOGGER = new DhLoggerBuilder().build();

    // Vulkan shader stage constants (avoids importing org.lwjgl.vulkan.VK10)
    private static final int VK_SHADER_STAGE_VERTEX_BIT = 0x00000001;
    private static final int VK_SHADER_STAGE_FRAGMENT_BIT = 0x00000010;

    private static VulkanRenderContext instance;

    /** The terrain rendering pipeline (replaces DhTerrainShaderProgram) */
    private GraphicsPipeline terrainPipeline;

    /** Whether the context has been initialized */
    private boolean initialized = false;

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

    /** @return true if VulkanMod is active and we should use Vulkan rendering */
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

    // ======================//
    // pipeline management //
    // ======================//

    /**
     * Creates the terrain rendering pipeline from DH's GLSL shaders.
     * Compiles standard.vert and flat_shaded.frag to SPIR-V.
     * <p>
     * DH vertex format (16 bytes per vertex):
     * <ul>
     * <li>Attr 0: uvec4 vPosition (unsigned short x4, 8 bytes)</li>
     * <li>Attr 1: vec4 color (unsigned byte x4 normalized, 4 bytes)</li>
     * <li>Attr 2: vec4 material (unsigned byte x4, 4 bytes)</li>
     * </ul>
     */
    private GraphicsPipeline createTerrainPipeline() {
        LOGGER.info("[DH-Vulkan] Creating terrain pipeline...");

        String vertSource = readShaderResource("shaders/standard.vert");
        String fragSource = readShaderResource("shaders/flat_shaded.frag");

        // Convert GLSL 1.50 → 4.50 for Vulkan/SPIR-V
        vertSource = convertGlslForVulkan(vertSource, true);
        fragSource = convertGlslForVulkan(fragSource, false);

        Pipeline.Builder builder = new Pipeline.Builder();
        builder.compileShaders("dh_terrain", vertSource, fragSource);

        // UBOs and samplers
        List<UBO> ubos = new ArrayList<>();
        List<ImageDescriptor> imageDescriptors = new ArrayList<>();

        // Binding 0: Main uniforms UBO
        AlignedStruct.Builder uboBuilder = new AlignedStruct.Builder();
        // VulkanMod type names: "matrix4x4", "float" (count=1-4 for
        // float/vec2/vec3/vec4), "int" (count=1-4)
        addUniform(uboBuilder, "matrix4x4", "uCombinedMatrix", 1);
        addUniform(uboBuilder, "float", "uModelOffset", 3); // vec3
        addUniform(uboBuilder, "float", "uWorldYOffset", 1); // float
        addUniform(uboBuilder, "float", "uMircoOffset", 1); // float
        addUniform(uboBuilder, "float", "uEarthRadius", 1); // float
        addUniform(uboBuilder, "int", "uIsWhiteWorld", 1); // int
        addUniform(uboBuilder, "float", "uClipDistance", 1); // float
        addUniform(uboBuilder, "int", "uDitherDhRendering", 1); // int
        addUniform(uboBuilder, "int", "uNoiseEnabled", 1); // int
        addUniform(uboBuilder, "int", "uNoiseSteps", 1); // int
        addUniform(uboBuilder, "float", "uNoiseIntensity", 1); // float
        addUniform(uboBuilder, "int", "uNoiseDropoff", 1); // int

        UBO mainUbo = uboBuilder.buildUBO(0, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        ubos.add(mainUbo);

        // Binding 1: LightMap sampler
        imageDescriptors.add(new ImageDescriptor(1, "sampler2D", "uLightMap",
                VTextureSelector.getTextureIdx("uLightMap")));

        builder.setUniforms(ubos, imageDescriptors);

        return builder.createGraphicsPipeline();
    }

    private void addUniform(AlignedStruct.Builder builder, String type, String name, int count) {
        Uniform.Info info = Uniform.createUniformInfo(type, name, count);
        try {
            info.setupSupplier();
        } catch (Exception ignored) {
        }
        if (!info.hasSupplier()) {
            // Provide a small dummy buffer supplier
            info.setBufferSupplier(() -> new MappedBuffer(4));
        }
        builder.addUniformInfo(info);
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
     * Transforms: version upgrade, layout location decorations,
     * individual uniforms to UBO block, sampler binding decorations.
     */
    static String convertGlslForVulkan(String source, boolean isVertex) {
        // Version upgrade
        source = source.replace("#version 150 core", "#version 450");
        source = source.replace("#version 150", "#version 450");

        if (isVertex) {
            // Vertex inputs with layout locations
            source = source.replaceFirst("in uvec4 vPosition;", "layout(location = 0) in uvec4 vPosition;");
            source = source.replaceFirst("in vec4 color;", "layout(location = 1) in vec4 color;");

            // Vertex outputs
            source = source.replaceFirst("out vec4 vPos;", "layout(location = 0) out vec4 vPos;");
            source = source.replaceFirst("out vec4 vertexColor;", "layout(location = 1) out vec4 vertexColor;");
            source = source.replaceFirst("out vec3 vertexWorldPos;", "layout(location = 2) out vec3 vertexWorldPos;");
            source = source.replaceFirst("out float vertexYPos;", "layout(location = 3) out float vertexYPos;");
        } else {
            // Fragment inputs
            source = source.replaceFirst("in vec4 vPos;", "layout(location = 0) in vec4 vPos;");
            source = source.replaceFirst("in vec4 vertexColor;", "layout(location = 1) in vec4 vertexColor;");
            source = source.replaceFirst("in vec3 vertexWorldPos;", "layout(location = 2) in vec3 vertexWorldPos;");

            // Remove gl_FragCoord redeclaration (built-in)
            source = source.replace("in vec4 gl_FragCoord;", "// gl_FragCoord is a built-in");

            // Fragment output
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

        return source;
    }
}
