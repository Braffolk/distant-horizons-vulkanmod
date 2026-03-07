#version 450

layout(location = 0) in vec2 TexCoord;
layout(location = 0) out vec4 fragColor;

layout(set = 0, binding = 1) uniform sampler2D gDhColorTexture;
layout(set = 0, binding = 2) uniform sampler2D gDhDepthTexture;

/**
 * Vulkan composite fragment shader.
 *
 * Samples DH's color and depth textures. If the depth is 1.0 (nothing was
 * drawn), the fragment is discarded — preserving MC's existing content.
 * A small depth bias pushes LOD depth slightly behind MC terrain, so MC
 * always wins the depth test where it exists.
 */
void main() {
    float dhDepth = texture(gDhDepthTexture, TexCoord).r;

    // Nothing drawn by DH here
    if (dhDepth >= 1.0) {
        discard;
    }

    fragColor = texture(gDhColorTexture, TexCoord);

    // Bias DH depth slightly behind so MC terrain always wins the depth test.
    // Where MC terrain hasn't loaded, its depth is 1.0 and LODs still render.
    gl_FragDepth = min(dhDepth + 0.0001, 1.0);
}
