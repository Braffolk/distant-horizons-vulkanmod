#version 450

/**
 * DH Shadow Vertex Shader — depth-only pass for Beryl's shadow map.
 *
 * Transforms DH LOD vertices by the light-space matrix so LODs cast
 * shadows in Beryl's shadow pipeline. Only position data is needed.
 *
 * Vertex format matches DH's 16-byte format:
 * - location 0: ivec4 (SHORT×4) — position
 * - location 1: vec4 (UBYTE×4) — color (unused)
 * - location 2: int (INT×1) — material (unused)
 */

layout(location = 0) in ivec4 vPosition;
layout(location = 1) in vec4 color;      // unused but must be declared for format match

layout(set = 0, binding = 0) uniform ShadowUniforms {
    mat4 uLightSpaceMatrix;   // lightProjection × lightView
    vec3 uModelOffset;        // camera-relative chunk offset
    float uWorldYOffset;      // DH world Y offset
};

void main()
{
    vec3 worldPos = vec3(vPosition.xyz) + uModelOffset;
    gl_Position = uLightSpaceMatrix * vec4(worldPos, 1.0);
}
