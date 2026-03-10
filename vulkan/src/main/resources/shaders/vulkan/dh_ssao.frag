#version 450

/**
 * Vulkan SSAO occlusion computation shader.
 * Port of shaders/ssao/ao.frag from GLSL 150 to GLSL 450.
 *
 * Reads DH's depth texture, reconstructs view-space positions,
 * computes surface normals via dFdxFine/dFdyFine, and samples
 * surrounding depth in a spiral pattern to compute occlusion.
 */

#define SAMPLE_MAX 64
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
};

// DH depth texture
layout(set = 0, binding = 1) uniform sampler2D uDepthMap;

const float EPSILON = 1.e-6;
const float GOLDEN_ANGLE = 2.39996323;
const vec3 MAGIC = vec3(0.06711056, 0.00583715, 52.9829189);
const float PI = 3.1415926538;
const float TAU = PI * 2.0;


vec3 unproject(vec4 pos) {
    return pos.xyz / pos.w;
}

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
    float rotatePhase = dither * TAU;
    float rStep = uRadius / uSampleCount;

    float ao = 0.0;
    int sampleCount = 0;
    float radius = rStep;
    for (int i = 0; i < clamp(uSampleCount, 1, SAMPLE_MAX); i++) {
        vec2 offset = vec2(
            sin(rotatePhase),
            cos(rotatePhase)
        ) * radius;

        radius += rStep;
        rotatePhase += GOLDEN_ANGLE;

        vec3 sampleViewPos = viewPos + vec3(offset, -0.1);
        vec3 sampleClipPos = unproject(uProj * vec4(sampleViewPos, 1.0)) * 0.5 + 0.5;
        sampleClipPos = saturate(sampleClipPos);

        float sampleClipDepth = textureLod(uDepthMap, sampleClipPos.xy, 0.0).r;
        if (sampleClipDepth >= 1.0 - EPSILON) continue;

        sampleClipPos.z = sampleClipDepth;
        sampleViewPos = unproject(uInvProj * vec4(sampleClipPos * 2.0 - 1.0, 1.0));

        vec3 diff = sampleViewPos - viewPos;
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
