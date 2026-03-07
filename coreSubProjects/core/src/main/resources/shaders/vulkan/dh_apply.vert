#version 450

layout(location = 0) in vec2 vPosition;
layout(location = 0) out vec2 TexCoord;

/**
 * Vulkan composite vertex shader — fullscreen quad.
 * Transforms NDC position to clip space and generates texture coordinates.
 */
void main() {
    gl_Position = vec4(vPosition, 0.0, 1.0);
    // Flip Y — Vulkan framebuffers have Y=0 at the top
    TexCoord = vec2(vPosition.x * 0.5 + 0.5, -vPosition.y * 0.5 + 0.5);
}
