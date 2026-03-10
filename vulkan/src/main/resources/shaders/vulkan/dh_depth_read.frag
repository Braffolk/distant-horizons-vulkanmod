#version 450

// Reads MC's depth-stencil texture and writes depth value to R32F color target.
// This runs in a separate pass where MC's depth is NOT an active attachment,
// avoiding the Vulkan spec violation of sampling from an active attachment.

layout(location = 0) in vec2 TexCoord;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform DummyUBO {
    int uDummy;
};

layout(binding = 1) uniform sampler2D gMcDepthTexture;

void main() {
    float d = texture(gMcDepthTexture, TexCoord).r;
    fragColor = vec4(d, 0.0, 0.0, 1.0);
}
