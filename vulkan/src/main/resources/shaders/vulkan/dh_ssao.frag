#version 450

/**
 * Vulkan SSAO occlusion computation shader.
 * Port of shaders/ssao/ao.frag from GLSL 150 to GLSL 450.
 *
 * Reads DH's depth texture, reconstructs view-space positions,
 * computes surface normals via dFdxFine/dFdyFine, and samples
 * surrounding depth in a spiral pattern to compute occlusion.
 *
 * Performance: sample offsets are pre-computed on the CPU and passed
 * via uniform array, eliminating per-sample sin/cos. The sample loop
 * uses direct screen-space offset + depth comparison instead of
 * double mat4 reproject chains.
 */

#define SAMPLE_MAX 4
#define saturate(x) (clamp((x), 0.0, 1.0))

layout(location = 0) in vec2 TexCoord;
layout(location = 0) out vec4 fragColor;

// UBO at binding 0 — shared between vertex and fragment
layout(set = 0, binding = 0) uniform SsaoUniforms {
    mat4 uInvProj;
    mat4 uProj;
    int uSampleCount;
    float uRadius;
    float uStrength;
    float uMinLight;
    float uBias;
    float uFadeDistanceInBlocks;
    // Pre-computed sample offsets (unit circle, spirally distributed)
    // Set on CPU once — avoids per-sample sin/cos on GPU
    vec4 uSampleOffsets[SAMPLE_MAX]; // xy = offset direction, zw = unused
};

// DH depth texture
layout(set = 0, binding = 1) uniform sampler2D uDepthMap;

const float EPSILON = 1.e-6;
const vec3 MAGIC = vec3(0.06711056, 0.00583715, 52.9829189);
const float PI = 3.1415926538;
const float TAU = PI * 2.0;


float InterleavedGradientNoise(const in vec2 pixel) {
    float x = dot(pixel, MAGIC.xy);
    return fract(MAGIC.z * fract(x));
}

vec3 calcViewPosition(const in vec3 clipPos) {
    vec4 viewPos = uInvProj * vec4(clipPos * 2.0 - 1.0, 1.0);
    return viewPos.xyz / viewPos.w;
}

float GetSpiralOcclusion(const in vec2 uv, const in vec3 viewPos, const in vec3 viewNormal) {
    float dither = InterleavedGradientNoise(gl_FragCoord.xy);
    // Rotation matrix from dither angle (computed once, not per sample)
    float angle = dither * TAU;
    float cosA = cos(angle);
    float sinA = sin(angle);

    float ao = 0.0;
    int sampleCount = 0;
    int count = clamp(uSampleCount, 1, SAMPLE_MAX);

    for (int i = 0; i < count; i++) {
        // Use pre-computed offset from CPU, rotate by dither angle
        vec2 baseOffset = uSampleOffsets[i].xy;
        vec2 offset = vec2(
            baseOffset.x * cosA - baseOffset.y * sinA,
            baseOffset.x * sinA + baseOffset.y * cosA
        );

        vec3 sampleViewPos = viewPos + vec3(offset, -0.1);
        // Project sample to clip space — single mat4×vec4
        vec4 sampleClip = uProj * vec4(sampleViewPos, 1.0);
        vec3 sampleClipPos = (sampleClip.xyz / sampleClip.w) * 0.5 + 0.5;
        sampleClipPos = saturate(sampleClipPos);

        float sampleClipDepth = textureLod(uDepthMap, sampleClipPos.xy, 0.0).r;
        if (sampleClipDepth >= 1.0 - EPSILON) continue;

        // Reconstruct actual sample position from sampled depth — single mat4×vec4
        vec4 actualClip = vec4(vec3(sampleClipPos.xy, sampleClipDepth) * 2.0 - 1.0, 1.0);
        vec4 actualView = uInvProj * actualClip;
        vec3 actualViewPos = actualView.xyz / actualView.w;

        vec3 diff = actualViewPos - viewPos;
        float sampleDist = length(diff);
        vec3 sampleNormal = diff / sampleDist;

        float sampleNoLm = max(dot(viewNormal, sampleNormal) - uBias, 0.0);
        float aoF = 1.0 - saturate(sampleDist / uRadius);
        ao += sampleNoLm * aoF;
        sampleCount++;
    }

    ao /= max(sampleCount, 1);
    ao = smoothstep(0.0, uStrength, ao);

    return ao * (1.0 - uMinLight);
}


void main() {
    float fragmentDepth = textureLod(uDepthMap, TexCoord, 0).r;
    float occlusion = 0.0;

    // Do not apply to sky
    if (fragmentDepth < 1.0) {
        vec3 viewPos = calcViewPosition(vec3(TexCoord, fragmentDepth));

        // Fading prevents banding/noise at extreme distance
        float distanceFromCamera = length(viewPos);
        float fadeDistance = uFadeDistanceInBlocks;
        if (distanceFromCamera < fadeDistance) {
            // dFdxFine/dFdyFine are native in GLSL 450 for Vulkan
            vec3 viewNormal = cross(dFdxFine(viewPos.xyz), dFdyFine(viewPos.xyz));
            viewNormal = normalize(viewNormal);
            occlusion = GetSpiralOcclusion(TexCoord, viewPos, viewNormal);

            // Linearly fade with distance
            occlusion *= (fadeDistance - distanceFromCamera) / fadeDistance;
        }
    }

    fragColor = vec4(vec3(1.0 - occlusion), 1.0);
}
