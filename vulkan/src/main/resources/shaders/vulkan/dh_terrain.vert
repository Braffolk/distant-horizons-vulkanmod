#version 450

/**
 * DH Terrain Vertex Shader — native Vulkan GLSL 450
 *
 * Port of DH's standard.vert with Vulkan-specific changes:
 * - UBO at binding 0 (no individual uniforms)
 * - Vertex attributes via layout(location = N)
 * - ivec4 position (SHORT×4 maps to signed in Vulkan)
 * - Lightmap sampler at binding 1
 */

// Vertex inputs (matches DH_TERRAIN_FORMAT: SHORT×4, UBYTE×4, INT×1)
layout(location = 0) in ivec4 vPosition;
layout(location = 1) in vec4 color;

// Outputs to fragment shader
layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec3 vertexWorldPos;
layout(location = 2) out vec4 vPos;

// Uniforms — shared with fragment shader
layout(set = 0, binding = 0) uniform DhUniforms {
    mat4 uCombinedMatrix;
#ifndef USE_PUSH_CONSTANTS
    vec3 uModelOffset;
#endif
    float uWorldYOffset;
    float uMircoOffset;
    float uEarthRadius;
    int uIsWhiteWorld;
    float uClipDistance;
    int uDitherDhRendering;
    int uNoiseEnabled;
    int uNoiseSteps;
    float uNoiseIntensity;
    int uNoiseDropoff;
};

#ifdef USE_PUSH_CONSTANTS
layout(push_constant) uniform PushData {
    vec3 uModelOffset;
};
#endif

// Lightmap sampler
layout(set = 0, binding = 1) uniform sampler2D uLightMap;


void main()
{
    // Pass raw position to fragment shader (for noise quantization)
    vPos = vec4(vPosition);

    // Cast to unsigned for bitwise metadata extraction
    uvec4 uPos = uvec4(vPosition);

    vertexWorldPos = vec3(vPosition.xyz) + uModelOffset;

    float vertexYPos = float(vPosition.y) + uWorldYOffset;

    // Metadata is packed in the 4th component (alpha)
    uint meta = uPos.a;

    // Micro offset: xyz 2-bit values packed as 0b00zzyyxx
    uint mirco = (meta & 0xFF00u) >> 8u;
    float mx = (mirco & 1u) != 0u ? uMircoOffset : 0.0;
    mx = (mirco & 2u) != 0u ? -mx : mx;
    float mz = (mirco & 16u) != 0u ? uMircoOffset : 0.0;
    mz = (mirco & 32u) != 0u ? -mz : mz;

    vertexWorldPos.x += mx;
    vertexWorldPos.z += mz;

    // Earth curvature
    if (uEarthRadius < -1.0 || uEarthRadius > 1.0)
    {
        float localRadius = uEarthRadius + vertexYPos;
        float phi = length(vertexWorldPos.xz) / localRadius;
        vertexWorldPos.y += (cos(phi) - 1.0) * localRadius;
        vertexWorldPos.xz = vertexWorldPos.xz * sin(phi) / phi;
    }

    // Lightmap lookup — VulkanMod uses texelFetch with integer coords
    uint lights = meta & 0xFFu;
    int iSkyLight = int(lights / 16u);
    int iBlockLight = int(lights) & 0xF;
    vertexColor = vec4(texelFetch(uLightMap, ivec2(iSkyLight, iBlockLight), 0).rgb, 1.0);

    // Apply vertex color (unless white world debug mode)
    if (uIsWhiteWorld == 0)
    {
        vertexColor *= color;
    }

    gl_Position = uCombinedMatrix * vec4(vertexWorldPos, 1.0);

    gl_ClipDistance[0] = length(vertexWorldPos) - uClipDistance;
}
