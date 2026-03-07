#version 450

layout(location = 0) in vec2 TexCoord;
layout(location = 0) out vec4 fragColor;

// UBO at binding 0
layout(std140, binding = 0) uniform CompositeUBO {
    mat4 uInvProj;    // inverse projection matrix for depth reconstruction
    int uDebugMode;   // 0=off, 1=depth, 2=ssao, 3=fog_alpha, 4=fog_color, 5=normals
};

layout(set = 0, binding = 1) uniform sampler2D gDhColorTexture;
layout(set = 0, binding = 2) uniform sampler2D gDhDepthTexture;
layout(set = 0, binding = 3) uniform sampler2D gSsaoTexture;
layout(set = 0, binding = 4) uniform sampler2D gFogTexture;

/**
 * Reconstruct view-space position from depth using inverse projection.
 * Same approach as the SSAO shader — proven to work.
 */
vec3 reconstructViewPos(vec2 uv, float depth) {
    vec3 clipPos = vec3(uv, depth) * 2.0 - 1.0;
    vec4 viewPos = uInvProj * vec4(clipPos, 1.0);
    return viewPos.xyz / viewPos.w;
}

/**
 * Reconstruct normals from depth using screen-space derivatives of view-space position.
 */
vec3 reconstructNormal(vec2 uv, float depth) {
    vec3 viewPos = reconstructViewPos(uv, depth);
    vec3 dx = dFdxFine(viewPos);
    vec3 dy = dFdyFine(viewPos);
    return normalize(cross(dx, dy)) * 0.5 + 0.5;
}

void main() {
    float dhDepth = texture(gDhDepthTexture, TexCoord).r;

    // Nothing drawn by DH here
    if (dhDepth >= 1.0) {
        discard;
    }

    if (uDebugMode == 0) {
        // Normal rendering
        fragColor = texture(gDhColorTexture, TexCoord);
    }
    else if (uDebugMode == 1) {
        // Depth visualization: reconstruct view-space Z, map to grayscale
        vec3 viewPos = reconstructViewPos(TexCoord, dhDepth);
        float viewDist = length(viewPos);
        // Map to visible range: 0-2000 blocks → 0-1
        float vis = 1.0 - clamp(viewDist / 2000.0, 0.0, 1.0);
        fragColor = vec4(vis, vis, vis, 1.0);
    }
    else if (uDebugMode == 2) {
        // SSAO buffer (white=no occlusion, black=full)
        float ao = texture(gSsaoTexture, TexCoord).r;
        fragColor = vec4(ao, ao, ao, 1.0);
    }
    else if (uDebugMode == 3) {
        // Fog alpha (white=full fog, black=no fog)
        float fogAlpha = texture(gFogTexture, TexCoord).a;
        fragColor = vec4(fogAlpha, fogAlpha, fogAlpha, 1.0);
    }
    else if (uDebugMode == 4) {
        // Fog color (raw RGB from fog pass)
        vec4 fog = texture(gFogTexture, TexCoord);
        fragColor = vec4(fog.rgb, 1.0);
    }
    else if (uDebugMode == 5) {
        // Reconstructed normals from depth
        fragColor = vec4(reconstructNormal(TexCoord, dhDepth), 1.0);
    }
    else {
        fragColor = texture(gDhColorTexture, TexCoord);
    }

    // Bias DH depth slightly behind so MC terrain always wins the depth test.
    gl_FragDepth = min(dhDepth + 0.0001, 1.0);
}
