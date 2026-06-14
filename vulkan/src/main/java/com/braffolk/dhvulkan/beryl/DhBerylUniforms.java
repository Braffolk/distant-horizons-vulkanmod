package com.braffolk.dhvulkan.beryl;

import com.braffolk.dhvulkan.core.data.RenderUniforms;
import com.braffolk.dhvulkan.compat.Compat;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.util.MappedBuffer;
import net.vulkanmod.vulkan.shader.Pipeline;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers Distant Horizons-specific uniforms for use with Beryl's shader system.
 *
 * These uniforms provide the same data that DH-compatible Iris shader packs expect:
 * - dhRenderDistance (int): DH's effective render distance in blocks
 * - dhNearPlane (float): Near clip plane distance for DH's projection
 * - dhFarPlane (float): Far clip plane distance for DH's projection
 * - dhProjection (mat4): DH's projection matrix
 * - dhProjectionInverse (mat4): Inverse of DH's projection
 * - dhPreviousProjection (mat4): DH's projection from the previous frame (for TAA)
 * - dhModelView (mat4): DH's model-view matrix
 * - dhModelViewInverse (mat4): Inverse of DH's model-view
 * - dhModelViewPrevious (mat4): DH's model-view from the previous frame
 *
 * Uniforms are stored in a dedicated UBO that is uploaded once per frame after
 * DH renders its LODs. The UBO is bound to a fixed descriptor set slot that
 * Beryl's shader system can discover and bind.
 */
public final class DhBerylUniforms {

    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod-Beryl");

    // Vulkan shader stages
    private static final int VK_SHADER_STAGE_VERTEX_BIT = 0x00000001;
    private static final int VK_SHADER_STAGE_FRAGMENT_BIT = 0x00000010;

    // UBO binding point for DH uniforms in Beryl's descriptor layout
    private static final int DH_UBO_BINDING = 8;

    /** Persistent mapped buffers for each DH uniform */
    private static final Map<String, MappedBuffer> uniformBuffers = new HashMap<>();

    /** The compiled UBO containing all DH uniforms */
    private static UBO dhUbo;

    /** Whether uniforms have been registered */
    private static boolean registered = false;

    /** Previous frame's matrices for temporal shader effects */
    private static float[] prevProjMatrix = new float[16];
    private static float[] prevModelViewMatrix = new float[16];
    private static boolean hasPrevFrame = false;

    /**
     * Register DH uniforms with Beryl's pipeline.
     * Creates a dedicated UBO at binding 8 containing all DH-specific uniforms.
     */
    public static void registerUniforms() {
        if (registered) return;

        try {
            AlignedStruct.Builder uboBuilder = new AlignedStruct.Builder();

            // DH projection (mat4 = 64 bytes)
            addUniform(uboBuilder, "matrix4x4", "dhProjection", 1, 64);
            // Inverse projection (mat4 = 64 bytes)
            addUniform(uboBuilder, "matrix4x4", "dhProjectionInverse", 1, 64);
            // Previous projection (mat4 = 64 bytes)
            addUniform(uboBuilder, "matrix4x4", "dhPreviousProjection", 1, 64);

            // DH model-view (mat4 = 64 bytes)
            addUniform(uboBuilder, "matrix4x4", "dhModelView", 1, 64);
            // Inverse model-view (mat4 = 64 bytes)
            addUniform(uboBuilder, "matrix4x4", "dhModelViewInverse", 1, 64);
            // Previous model-view (mat4 = 64 bytes)
            addUniform(uboBuilder, "matrix4x4", "dhModelViewPrevious", 1, 64);

            // Scalar uniforms
            addUniform(uboBuilder, "int", "dhRenderDistance", 1, 4);
            addUniform(uboBuilder, "float", "dhNearPlane", 1, 4);
            addUniform(uboBuilder, "float", "dhFarPlane", 1, 4);

            dhUbo = uboBuilder.buildUBO(DH_UBO_BINDING,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);

            // Set buffer suppliers for all custom uniforms
            Compat.setUniformSuppliers(dhUbo, uniformBuffers);

            registered = true;
            LOGGER.info("[DH-Vulkan-Beryl] DH uniforms registered at UBO binding {}", DH_UBO_BINDING);
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan-Beryl] Failed to register DH uniforms", e);
        }
    }

    private static void addUniform(AlignedStruct.Builder builder, String type, String name,
                                   int count, int byteSize) {
        MappedBuffer mb = new MappedBuffer(byteSize);
        uniformBuffers.put(name, mb);
        Compat.addUniformWithBuffer(builder, type, name, count, () -> mb);
    }

    /**
     * Update DH uniform values from the current frame's render parameters.
     * Called from VulkanRenderEngine after LOD rendering.
     *
     * @param uniforms the current frame's render uniforms
     */
    public static void updateUniforms(RenderUniforms uniforms) {
        if (!registered || uniforms == null) return;

        try {
            // Store previous frame matrices for temporal effects
            if (hasPrevFrame) {
                System.arraycopy(prevProjMatrix, 0, uniformBuffers.get("dhPreviousProjection").buffer, 0, 64);
                System.arraycopy(prevModelViewMatrix, 0, uniformBuffers.get("dhModelViewPrevious").buffer, 0, 64);
            }

            // DH projection matrix (column-major std140)
            float[] proj = mat4ToArray(uniforms.dhProjectionMatrix);
            copyToBuffer("dhProjection", proj);
            System.arraycopy(proj, 0, prevProjMatrix, 0, 16);

            // Inverse projection
            float[] invProj = invertMatrix(proj);
            copyToBuffer("dhProjectionInverse", invProj);

            // DH model-view matrix
            float[] mv = mat4ToArray(uniforms.dhModelViewMatrix);
            copyToBuffer("dhModelView", mv);
            System.arraycopy(mv, 0, prevModelViewMatrix, 0, 16);

            // Inverse model-view
            float[] invMv = invertMatrix(mv);
            copyToBuffer("dhModelViewInverse", invMv);

            // Scalar uniforms
            MappedBuffer rdBuf = uniformBuffers.get("dhRenderDistance");
            if (rdBuf != null) {
                int renderDist = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
                rdBuf.putInt(0, renderDist);
            }

            MappedBuffer nearBuf = uniformBuffers.get("dhNearPlane");
            if (nearBuf != null) {
                nearBuf.putFloat(0, 0.05f);
            }

            MappedBuffer farBuf = uniformBuffers.get("dhFarPlane");
            if (farBuf != null) {
                int renderDist = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
                farBuf.putFloat(0, renderDist * 3000.0f);
            }

            hasPrevFrame = true;
        } catch (Exception e) {
            LOGGER.debug("[DH-Vulkan-Beryl] Failed to update uniforms: {}", e.getMessage());
        }
    }

    private static float[] mat4ToArray(com.seibel.distanthorizons.core.util.math.Mat4f mat) {
        return new float[] {
            mat.m00, mat.m10, mat.m20, mat.m30,
            mat.m01, mat.m11, mat.m21, mat.m31,
            mat.m02, mat.m12, mat.m22, mat.m32,
            mat.m03, mat.m13, mat.m23, mat.m33
        };
    }

    private static void copyToBuffer(String name, float[] values) {
        MappedBuffer buf = uniformBuffers.get(name);
        if (buf == null) return;
        for (int i = 0; i < Math.min(values.length, 16); i++) {
            buf.putFloat(i * 4, values[i]);
        }
    }

    /**
     * Simple 4x4 matrix inversion. Used for computing dhProjectionInverse
     * and dhModelViewInverse on the CPU side.
     */
    private static float[] invertMatrix(float[] m) {
        float[] inv = new float[16];

        inv[0]  =  m[5]*m[10]*m[15] - m[5]*m[11]*m[14] - m[9]*m[6]*m[15] + m[9]*m[7]*m[14] + m[13]*m[6]*m[11] - m[13]*m[7]*m[10];
        inv[4]  = -m[4]*m[10]*m[15] + m[4]*m[11]*m[14] + m[8]*m[6]*m[15] - m[8]*m[7]*m[14] - m[12]*m[6]*m[11] + m[12]*m[7]*m[10];
        inv[8]  =  m[4]*m[9]*m[15]  - m[4]*m[11]*m[13] - m[8]*m[5]*m[15] + m[8]*m[7]*m[13] + m[12]*m[5]*m[11] - m[12]*m[7]*m[9];
        inv[12] = -m[4]*m[9]*m[14]  + m[4]*m[10]*m[13] + m[8]*m[5]*m[14] - m[8]*m[6]*m[13] - m[12]*m[5]*m[10] + m[12]*m[6]*m[9];

        inv[1]  = -m[1]*m[10]*m[15] + m[1]*m[11]*m[14] + m[9]*m[2]*m[15] - m[9]*m[3]*m[14] - m[13]*m[2]*m[11] + m[13]*m[3]*m[10];
        inv[5]  =  m[0]*m[10]*m[15] - m[0]*m[11]*m[14] - m[8]*m[2]*m[15] + m[8]*m[3]*m[14] + m[12]*m[2]*m[11] - m[12]*m[3]*m[10];
        inv[9]  = -m[0]*m[9]*m[15]  + m[0]*m[11]*m[13] + m[8]*m[1]*m[15] - m[8]*m[3]*m[13] - m[12]*m[1]*m[11] + m[12]*m[3]*m[9];
        inv[13] =  m[0]*m[9]*m[14]  - m[0]*m[10]*m[13] - m[8]*m[1]*m[14] + m[8]*m[2]*m[13] + m[12]*m[1]*m[10] - m[12]*m[2]*m[9];

        inv[2]  =  m[1]*m[6]*m[15] - m[1]*m[7]*m[14] - m[5]*m[2]*m[15] + m[5]*m[3]*m[14] + m[13]*m[2]*m[7] - m[13]*m[3]*m[6];
        inv[6]  = -m[0]*m[6]*m[15] + m[0]*m[7]*m[14] + m[4]*m[2]*m[15] - m[4]*m[3]*m[14] - m[12]*m[2]*m[7] + m[12]*m[3]*m[6];
        inv[10] =  m[0]*m[5]*m[15] - m[0]*m[7]*m[13] - m[4]*m[1]*m[15] + m[4]*m[3]*m[13] + m[12]*m[1]*m[7] - m[12]*m[3]*m[5];
        inv[14] = -m[0]*m[5]*m[14] + m[0]*m[6]*m[13] + m[4]*m[1]*m[14] - m[4]*m[2]*m[13] - m[12]*m[1]*m[6] + m[12]*m[2]*m[5];

        inv[3]  = -m[1]*m[6]*m[11] + m[1]*m[7]*m[10] + m[5]*m[2]*m[11] - m[5]*m[3]*m[10] - m[9]*m[2]*m[7]  + m[9]*m[3]*m[6];
        inv[7]  =  m[0]*m[6]*m[11] - m[0]*m[7]*m[10] - m[4]*m[2]*m[11] + m[4]*m[3]*m[10] + m[8]*m[2]*m[7]  - m[8]*m[3]*m[6];
        inv[11] = -m[0]*m[5]*m[11] + m[0]*m[7]*m[9]  + m[4]*m[1]*m[11] - m[4]*m[3]*m[9]  - m[8]*m[1]*m[7]  + m[8]*m[3]*m[5];
        inv[15] =  m[0]*m[5]*m[10] - m[0]*m[6]*m[9]  - m[4]*m[1]*m[10] + m[4]*m[2]*m[9]  + m[8]*m[1]*m[6]  - m[8]*m[2]*m[5];

        float det = m[0]*inv[0] + m[1]*inv[4] + m[2]*inv[8] + m[3]*inv[12];
        if (Math.abs(det) < 1e-10f) {
            // Singular matrix — return identity
            java.util.Arrays.fill(inv, 0);
            inv[0] = inv[5] = inv[10] = inv[15] = 1.0f;
            return inv;
        }

        float invDet = 1.0f / det;
        for (int i = 0; i < 16; i++) {
            inv[i] *= invDet;
        }

        return inv;
    }

    /** Clean up all uniform buffer resources */
    public static void cleanup() {
        for (MappedBuffer mb : uniformBuffers.values()) {
            if (mb != null && mb.buffer != null) {
                org.lwjgl.system.MemoryUtil.memFree(mb.buffer);
            }
        }
        uniformBuffers.clear();
        registered = false;
        hasPrevFrame = false;
    }
}
