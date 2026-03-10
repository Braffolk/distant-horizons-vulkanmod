#version 450

/**
 * Vulkan SSAO bilateral blur + apply shader.
 * Port of shaders/ssao/apply.frag from GLSL 150 to GLSL 450.
 *
 * Reads the raw SSAO texture and DH's depth texture, applies a
 * depth-aware bilateral Gaussian blur, and outputs the blurred
 * occlusion factor in the alpha channel for multiplicative blending
 * onto DH's color buffer.
 */

layout(location = 0) in vec2 TexCoord;
layout(location = 0) out vec4 fragColor;

// UBO at binding 0 — blur parameters
layout(set = 0, binding = 0) uniform SsaoApplyUniforms {
    vec2 gViewSize;
    int gBlurRadius;
    float gNear;
    float gFar;
    int uDebugMode;  // 0=normal, 1=red-green heat map overlay
};

// Raw SSAO texture from pass 1
layout(set = 0, binding = 1) uniform sampler2D gSSAOMap;
// DH depth texture
layout(set = 0, binding = 2) uniform sampler2D gDepthMap;


float linearizeDepth(const in float depth) {
    return (gNear * gFar) / (depth * (gNear - gFar) + gFar);
}

float Gaussian(const in float sigma, const in float x) {
    return exp(-(x * x) / (2.0 * (sigma * sigma)));
}

float BilateralGaussianBlur(const in vec2 texcoord, const in float linearDepth, const in float g_sigmaV) {
    float g_sigmaX = 1.6;
    float g_sigmaY = 1.6;

    int radius = clamp(gBlurRadius, 1, 3);

    vec2 pixelSize = 1.0 / gViewSize;

    float accum = 0.0;
    float total = 0.0;
    for (int iy = -radius; iy <= radius; iy++) {
        float fy = Gaussian(g_sigmaY, iy);

        for (int ix = -radius; ix <= radius; ix++) {
            float fx = Gaussian(g_sigmaX, ix);

            vec2 sampleTex = texcoord + ivec2(ix, iy) * pixelSize;
            float sampleValue = textureLod(gSSAOMap, sampleTex, 0).r;
            float sampleDepth = textureLod(gDepthMap, sampleTex, 0).r;
            float sampleLinearDepth = linearizeDepth(sampleDepth);

            float depthDiff = abs(sampleLinearDepth - linearDepth);
            float fv = Gaussian(g_sigmaV, depthDiff);

            float weight = fx * fy * fv;
            accum += weight * sampleValue;
            total += weight;
        }
    }

    if (total <= 1.e-4) return 1.0;
    return accum / total;
}


void main() {
    fragColor = vec4(1.0);

    float fragmentDepth = textureLod(gDepthMap, TexCoord, 0).r;

    // Only apply SSAO to LODs, not to the sky (depth == 1.0)
    if (fragmentDepth < 1.0) {
        float ao;
        if (gBlurRadius > 0) {
            float fragmentDepthLinear = linearizeDepth(fragmentDepth);
            ao = BilateralGaussianBlur(TexCoord, fragmentDepthLinear, 1.6);
        } else {
            ao = textureLod(gSSAOMap, TexCoord, 0).r;
        }

        if (uDebugMode != 0) {
            // Debug: red = heavily occluded, green = no occlusion
            float occlusion = 1.0 - ao;
            fragColor = vec4(occlusion, ao, 0.0, 0.85);
        } else {
            fragColor.a = ao;
        }
    }
}
