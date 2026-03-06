package net.vulkanmod.mixin.render.vertex;

import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Vec3i;
import net.vulkanmod.interfaces.ExtendedVertexBuilder;
import net.vulkanmod.mixin.matrix.PoseAccessor;
import net.vulkanmod.render.util.MathUtil;
import net.vulkanmod.render.vertex.format.I32_SNorm;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.*;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderM
        implements VertexConsumer, ExtendedVertexBuilder {

    @Shadow private boolean fastFormat;
    @Shadow private boolean fullFormat;
    @Shadow private VertexFormat format;

    @Shadow protected abstract long beginVertex();

    @Shadow private int elementsToFill;
    @Shadow @Final private int initialElementsToFill;

    @Shadow protected abstract long beginElement(VertexFormatElement vertexFormatElement);

    private long ptr;

    public void vertex(float x, float y, float z, int packedColor, float u, float v, int overlay, int light, int packedNormal) {
        this.ptr = this.beginVertex();

        if (this.format == DefaultVertexFormat.NEW_ENTITY) {
            MemoryUtil.memPutFloat(ptr + 0, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);

            MemoryUtil.memPutInt(ptr + 12, packedColor);

            MemoryUtil.memPutFloat(ptr + 16, u);
            MemoryUtil.memPutFloat(ptr + 20, v);

            MemoryUtil.memPutInt(ptr + 24, overlay);

            MemoryUtil.memPutInt(ptr + 28, light);
            MemoryUtil.memPutInt(ptr + 32, packedNormal);

        }
        else {
            this.elementsToFill = this.initialElementsToFill;

            this.position(x, y, z);
            this.fastColor(packedColor);
            this.fastUv(u, v);
            this.fastOverlay(overlay);
            this.light(light);
            this.fastNormal(packedNormal);

//            throw new RuntimeException("unaccepted format: " + this.format);
        }

    }

    public void vertex(float x, float y, float z, float u, float v, int packedColor, int light) {
        this.ptr = this.beginVertex();

        MemoryUtil.memPutFloat(ptr + 0, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        MemoryUtil.memPutFloat(ptr + 8, z);

        MemoryUtil.memPutFloat(ptr + 12, u);
        MemoryUtil.memPutFloat(ptr + 16, v);

        MemoryUtil.memPutInt(ptr + 20, packedColor);

        MemoryUtil.memPutInt(ptr + 24, light);
    }

    public void position(float x, float y, float z) {
        MemoryUtil.memPutFloat(ptr + 0, x);
        MemoryUtil.memPutFloat(ptr + 4, y);
        MemoryUtil.memPutFloat(ptr + 8, z);
    }

    public void fastColor(int packedColor) {
        long ptr = this.beginElement(VertexFormatElement.COLOR);
        if (ptr != -1L) {
            MemoryUtil.memPutInt(ptr, packedColor);
        }
    }

    public void fastUv(float u, float v) {
        long ptr = this.beginElement(VertexFormatElement.UV0);
        if (ptr != -1L) {
            MemoryUtil.memPutFloat(ptr, u);
            MemoryUtil.memPutFloat(ptr + 4, v);
        }
    }

    public void fastOverlay(int o) {
        long ptr = this.beginElement(VertexFormatElement.UV1);
        if (ptr != -1L) {
            MemoryUtil.memPutInt(ptr, o);
        }
    }

    public void light(int l) {
        long ptr = this.beginElement(VertexFormatElement.UV2);
        if (ptr != -1L) {
            MemoryUtil.memPutInt(ptr, l);
        }
    }

    public void fastNormal(int packedNormal) {
        long ptr = this.beginElement(VertexFormatElement.NORMAL);
        if (ptr != -1L) {
            MemoryUtil.memPutInt(ptr, packedNormal);
        }
    }

    /**
     * @author
     */
    @Overwrite
    public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY, float normalZ) {
        if (this.fastFormat) {
            long ptr = this.beginVertex();
            MemoryUtil.memPutFloat(ptr + 0, x);
            MemoryUtil.memPutFloat(ptr + 4, y);
            MemoryUtil.memPutFloat(ptr + 8, z);

            MemoryUtil.memPutInt(ptr + 12, color);

            MemoryUtil.memPutFloat(ptr + 16, u);
            MemoryUtil.memPutFloat(ptr + 20, v);

            byte i;
            if (this.fullFormat) {
                MemoryUtil.memPutInt(ptr + 24, overlay);
                i = 28;
            } else {
                i = 24;
            }

            MemoryUtil.memPutInt(ptr + i, light);

            int temp = I32_SNorm.packNormal(normalX, normalY, normalZ);
            MemoryUtil.memPutInt(ptr + i + 4, temp);
        } else {
            VertexConsumer.super.addVertex(x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);
        }
    }

    @Override
    public void putBulkData(PoseStack.Pose matrixEntry, BakedQuad quad, float[] brightness, float red, float green,
                            float blue, float alpha, int[] lights, int overlay, boolean useQuadColorData) {
        putQuadData(matrixEntry, quad, brightness, red, green, blue, alpha, lights, overlay, useQuadColorData);
    }

    @SuppressWarnings("UnreachableCode")
    @Unique
    private void putQuadData(PoseStack.Pose matrixEntry, BakedQuad quad, float[] brightness, float red, float green, float blue, float alpha, int[] lights, int overlay, boolean useQuadColorData) {
        int[] quadData = quad.vertices();
        Vec3i vec3i = quad.direction().getUnitVec3i();
        Matrix4f matrix4f = matrixEntry.pose();

        boolean trustedNormals = ((PoseAccessor)(Object)matrixEntry).trustedNormals();
        int normal = MathUtil.packTransformedNorm(matrixEntry.normal(), trustedNormals, vec3i.getX(), vec3i.getY(), vec3i.getZ());

        for (int k = 0; k < 4; ++k) {
            float r, g, b;

            float quadR, quadG, quadB;

            int i = k * 8;
            float x = Float.intBitsToFloat(quadData[i]);
            float y = Float.intBitsToFloat(quadData[i + 1]);
            float z = Float.intBitsToFloat(quadData[i + 2]);

            float tx = MathUtil.transformX(matrix4f, x, y, z);
            float ty = MathUtil.transformY(matrix4f, x, y, z);
            float tz = MathUtil.transformZ(matrix4f, x, y, z);

            if (useQuadColorData) {
                int color = quadData[i + 3];
                quadR = ColorUtil.RGBA.unpackR(color);
                quadG = ColorUtil.RGBA.unpackG(color);
                quadB = ColorUtil.RGBA.unpackB(color);
                r = quadR * brightness[k] * red;
                g = quadG * brightness[k] * green;
                b = quadB * brightness[k] * blue;
            } else {
                r = brightness[k] * red;
                g = brightness[k] * green;
                b = brightness[k] * blue;
            }

            int color = ColorUtil.RGBA.pack(r, g, b, alpha);

            int light = lights[k];
            float u = Float.intBitsToFloat(quadData[i + 4]);
            float v = Float.intBitsToFloat(quadData[i + 5]);

            this.vertex(tx, ty, tz, color, u, v, overlay, light, normal);
        }
    }

}
