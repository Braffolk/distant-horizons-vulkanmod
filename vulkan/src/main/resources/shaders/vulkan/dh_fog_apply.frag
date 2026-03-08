#version 450

layout(location = 0) in vec2 TexCoord;
layout(location = 0) out vec4 fragColor;

// UBO at binding 0 (unused but required by pipeline layout)
layout(std140, binding = 0) uniform FogApplyUBO {
    vec2 gViewSize;
};

// Fog texture at binding 1
layout(binding = 1) uniform sampler2D uFogTexture;

// DH depth texture at binding 2
layout(binding = 2) uniform sampler2D uDepthTexture;

/**
 * Fog application shader — merges rendered fog onto DH's LODs.
 * Depth-gated: only applies fog where LODs were actually drawn (depth < 1.0).
 */
void main()
{
    fragColor = vec4(0.0);

    float fragmentDepth = textureLod(uDepthTexture, TexCoord, 0).r;
    if (fragmentDepth < 1.0)
    {
        fragColor = texture(uFogTexture, TexCoord);
    }
}
