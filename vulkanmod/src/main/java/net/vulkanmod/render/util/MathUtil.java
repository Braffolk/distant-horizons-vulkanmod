package net.vulkanmod.render.util;

import net.vulkanmod.render.vertex.format.I32_SNorm;
import org.joml.Math;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public class MathUtil {

    public static float clamp(float min, float max, float x) {
        return Math.min(Math.max(x, min), max);
    }

    public static int clamp(int min, int max, int x) {
        return Math.min(Math.max(x, min), max);
    }

    public static float saturate(float x) {
        return clamp(0.0f, 1.0f, x);
    }

    public static float lerp(float v0, float v1, float t) {
        return v0 + t * (v1 - v0);
    }

    // JOML Math util

    public static float transformX(Matrix4f mat, float x, float y, float z) {
        return Math.fma(mat.m00(), x, Math.fma(mat.m10(), y, Math.fma(mat.m20(), z, mat.m30())));
    }

    public static float transformY(Matrix4f mat, float x, float y, float z) {
        return Math.fma(mat.m01(), x, Math.fma(mat.m11(), y, Math.fma(mat.m21(), z, mat.m31())));
    }

    public static float transformZ(Matrix4f mat, float x, float y, float z) {
        return Math.fma(mat.m02(), x, Math.fma(mat.m12(), y, Math.fma(mat.m22(), z, mat.m32())));
    }

    public static int packTransformedNorm(Matrix3f mat, boolean trustedNormals, float x, float y, float z) {
        float nx = transformNormX(mat, x, y, z);
        float ny = transformNormY(mat, x, y, z);
        float nz = transformNormZ(mat, x, y, z);

        if (!trustedNormals) {
            float scalar = Math.invsqrt(Math.fma(nx, nx, Math.fma(ny, ny, nz * nz)));
            nx = nx * scalar;
            ny = ny * scalar;
            nz = nz * scalar;
        }

        int packedNormal = I32_SNorm.packNormal(nx, ny, nz);
        return packedNormal;
    }

    public static float transformNormX(Matrix3f mat, float x, float y, float z) {
        return Math.fma(mat.m00(), x, Math.fma(mat.m10(), y, mat.m20() * z));
    }

    public static float transformNormY(Matrix3f mat, float x, float y, float z) {
        return Math.fma(mat.m01(), x, Math.fma(mat.m11(), y, mat.m21() * z));
    }

    public static float transformNormZ(Matrix3f mat, float x, float y, float z) {
        return Math.fma(mat.m02(), x, Math.fma(mat.m12(), y, mat.m22() * z));
    }
}
