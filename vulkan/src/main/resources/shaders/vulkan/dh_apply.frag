#version 450

layout(location = 0) in vec2 TexCoord;
layout(location = 0) out vec4 fragColor;

// UBO at binding 0
layout(std140, binding = 0) uniform CompositeUBO {
    mat4 uInvProj;    // inverse of DH's projection matrix (for view-space reconstruction)
    mat4 uMcProj;     // MC's projection matrix (for remapping DH depth → MC depth)
    int uDebugMode;   // 0=off, 1=depth, 2=ssao, 3=fog_alpha, 4=fog_color, 5=normals, 6=mc_depth
    int uUseMcDepth;  // 0=no MC depth comparison, 1=discard where MC terrain is closer
};

layout(set = 0, binding = 1) uniform sampler2D gDhColorTexture;
layout(set = 0, binding = 2) uniform sampler2D gDhDepthTexture;
layout(set = 0, binding = 3) uniform sampler2D gSsaoTexture;
layout(set = 0, binding = 4) uniform sampler2D gFogTexture;
layout(set = 0, binding = 5) uniform sampler2D gMcDepthTexture;

/**
 * Reconstruct view-space position from DH depth using DH's inverse projection.
 */
vec3 reconstructViewPos(vec2 uv, float depth) {
    vec3 clipPos = vec3(uv, depth) * 2.0 - 1.0;
    vec4 viewPos = uInvProj * vec4(clipPos, 1.0);
    return viewPos.xyz / viewPos.w;
}

/**
 * Remap DH depth to MC-compatible depth.
 * DH renders with dhProjectionMatrix (extended near/far clip planes).
 * MC uses its own projection. To write gl_FragDepth that is comparable
 * to MC's depth buffer, we unproject DH depth → view space → reproject
 * with MC's projection.
 */
float remapDepthDhToMc(vec2 uv, float dhDepth) {
    // Unproject from DH clip space to view space
    vec4 viewPos = uInvProj * vec4(vec3(uv, dhDepth) * 2.0 - 1.0, 1.0);
    viewPos /= viewPos.w;
    // Reproject to MC clip space
    vec4 mcClip = uMcProj * viewPos;
    return (mcClip.z / mcClip.w) * 0.5 + 0.5;
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

    // MC depth visualization — must render EVERYWHERE on screen, not just on LODs
    if (uDebugMode == 6) {
        float mcDepth = texture(gMcDepthTexture, TexCoord).r;
        // Exaggerate differences: pow makes near-1.0 values more visible
        float vis = 1.0 - pow(mcDepth, 256.0);
        fragColor = vec4(vis, vis, vis, 1.0);
        gl_FragDepth = 0.0; // always on top
        return;
    }

    // Nothing drawn by DH here
    if (dhDepth >= 1.0) {
        discard;
    }

    // MC depth comparison: if MC rendered terrain at this pixel, discard the LOD.
    if (uUseMcDepth != 0) {
        float mcDepth = texture(gMcDepthTexture, TexCoord).r;
        if (mcDepth < 0.9999) {
            discard;  // MC terrain exists here → hide LOD
        }
    }

    if (uDebugMode == 0) {
        // Normal rendering
        fragColor = texture(gDhColorTexture, TexCoord);
    }
    else if (uDebugMode == 1) {
        // Depth visualization: reconstruct view-space Z, map to grayscale
        vec3 viewPos = reconstructViewPos(TexCoord, dhDepth);
        float viewDist = length(viewPos);
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

    // Remap DH depth to MC-compatible depth for correct occlusion against MC terrain.
    // DH uses dhProjectionMatrix with extended near/far clip planes.
    // Clamp to 0.999 so LODs beyond MC's far plane still have depth < sky (1.0),
    // ensuring clouds (which get depth ~1.0 or are frustum-clipped at MC's far plane)
    // correctly fail the depth test against distant LODs.
    float mcCompatibleDepth = remapDepthDhToMc(TexCoord, dhDepth);
    gl_FragDepth = clamp(mcCompatibleDepth, 0.0, 0.999);
}
