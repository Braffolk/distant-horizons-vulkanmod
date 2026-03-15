#version 450

layout(location = 0) in vec2 TexCoord;
layout(location = 0) out vec4 fragColor;

// UBO at binding 0
layout(std140, binding = 0) uniform CompositeUBO {
    mat4 uInvProj;          // inverse of DH's projection matrix (for view-space reconstruction)
    mat4 uMcProj;           // MC's projection matrix (for remapping DH depth → MC depth)
    mat4 uInvMcMvmProj;     // inverse of MC's modelview-projection matrix (for MC fragment distance)
    mat4 uRemapProj;        // = uMcProj * uInvProj — fused unproject+reproject (1× mat4 instead of 2×)
    int uDebugMode;         // 0=off, 1=depth, 2=ssao, 3=fog_alpha, 4=fog_color, 5=normals, 6=mc_depth
    int uUseMcDepth;        // 0=no MC depth comparison, 1=use MC depth for fade
    float uStartFadeBlockDist;  // distance where DH fade begins (blocks)
    float uEndFadeBlockDist;    // distance where DH fade ends (blocks)
    float uStartFadeBlockDistSq; // squared, for dot() instead of length()
    float uEndFadeBlockDistSq;   // squared, for dot() instead of length()
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
    // Single mat4 multiply: uRemapProj = uMcProj * uInvProj (pre-multiplied on CPU)
    vec4 mcClip = uRemapProj * vec4(vec3(uv, dhDepth) * 2.0 - 1.0, 1.0);
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

    // MC depth comparison with distance-based fade (matching DH base's vanillaFade.frag).
    // Instead of a hard discard, smoothly fade LODs based on MC fragment distance.
    float fadeMultiplier = 1.0;
    if (uUseMcDepth != 0) {
        float mcDepth = texture(gMcDepthTexture, TexCoord).r;
        if (mcDepth < 1.0) {
            // MC terrain exists — calculate view-space distance for fade
            vec4 mcNdc = vec4(TexCoord * 2.0 - 1.0, mcDepth * 2.0 - 1.0, 1.0);
            vec4 mcViewPos = uInvMcMvmProj * mcNdc;
            mcViewPos /= mcViewPos.w;
            // Use squared distance to avoid per-pixel sqrt
            float mcFragDistSq = dot(mcViewPos.xyz, mcViewPos.xyz);

            // smoothstep on squared values (thresholds pre-squared on CPU)
            fadeMultiplier = smoothstep(uStartFadeBlockDistSq, uEndFadeBlockDistSq, mcFragDistSq);
            if (fadeMultiplier <= 0.0) {
                discard;  // fully within MC terrain range
            }
        } else {
            // No MC terrain at this pixel (open sky behind LODs).
            // Phase 1 already composited LODs here with depth 1.0.
            // Weather/particles may have rendered on top since Phase 1.
            // Do NOT redraw — discard to preserve whatever is currently on screen.
            // This fixed rain and snow rendering on NVIDIA.
            discard;
        }
    }

    if (uDebugMode == 0) {
        // Normal rendering — apply distance fade for MC/DH transition
        fragColor = texture(gDhColorTexture, TexCoord);
        fragColor *= fadeMultiplier;  // premultiplied alpha: scale RGB+A together
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

    if (uUseMcDepth != 0) {
        float mcDepth = texture(gMcDepthTexture, TexCoord).r;
        if (mcDepth < 1.0) {
            // MC terrain exists at this pixel — write remapped depth for fade transition.
            float mcCompatibleDepth = remapDepthDhToMc(TexCoord, dhDepth);
            gl_FragDepth = clamp(mcCompatibleDepth, 0.0, 0.999);
        } else {
            // No MC terrain (open sky) — write 1.0 so weather, particles, and
            // other effects rendered after this composite pass freely via LEQUAL.
            gl_FragDepth = 1.0;
        }
    } else {
        // Phase 1 (without MC depth): write far-plane depth so MC terrain AND
        // weather both render freely on top via LEQUAL. LOD colors are still
        // blended onto the swapchain — only the depth is transparent.
        // Phase 2b re-composites with proper depth after weather renders.
        gl_FragDepth = 1.0;
    }
}
