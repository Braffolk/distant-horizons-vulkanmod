#version 450

/**
 * DH Shadow Fragment Shader — depth-only pass for Beryl's shadow map.
 *
 * Beryl's shadow framebuffer has a R8G8B8A8_SRGB color attachment (format 43)
 * alongside the depth attachment. We write white to the color attachment
 * (matching Beryl's clear color of 1,1,1,1) and let the depth write happen
 * automatically via the depth attachment.
 */

layout(location = 0) out vec4 fragColor;

void main()
{
    fragColor = vec4(1.0);
}
