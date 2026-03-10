#version 450

layout(location = 0) in vec2 vPosition; // bound but unused — positions from gl_VertexIndex
layout(location = 0) out vec2 TexCoord;

/**
 * Vulkan composite vertex shader — fullscreen triangle via gl_VertexIndex.
 * Generates an oversized triangle covering the entire viewport.
 * No vertex buffer data is read — positions are computed from the vertex index.
 */
void main() {
    // Vertex 0: (-1,-1), Vertex 1: (3,-1), Vertex 2: (-1,3)
    vec2 pos = vec2(
        (gl_VertexIndex == 1) ? 3.0 : -1.0,
        (gl_VertexIndex == 2) ? 3.0 : -1.0
    );
    gl_Position = vec4(pos, 0.0, 1.0);
    TexCoord = vec2(pos.x * 0.5 + 0.5, -pos.y * 0.5 + 0.5);
}
