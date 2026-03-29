#version 450

/**
 * DH Terrain Fragment Shader — native Vulkan GLSL 450
 *
 * Port of DH's flat_shaded.frag with Vulkan-specific changes:
 * - UBO at binding 0 (no individual uniforms)
 * - bool → int for std140 layout
 * - Inputs/outputs via layout(location = N)
 * - gl_FragCoord is built-in (no redeclaration)
 */

// Inputs from vertex shader
layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec3 vertexWorldPos;
layout(location = 2) in vec4 vPos;
layout(location = 3) in vec2 vLightLevels;

// Output
layout(location = 0) out vec4 fragColor;

// Uniforms — shared with vertex shader
layout(set = 0, binding = 0) uniform DhUniforms {
    mat4 uCombinedMatrix;
#ifndef USE_PUSH_CONSTANTS
    vec3 uModelOffset;
#endif
    float uWorldYOffset;
    float uMircoOffset;
    float uEarthRadius;
    int uIsWhiteWorld;
    float uClipDistance;
    int uDitherDhRendering;
    int uNoiseEnabled;
    int uNoiseSteps;
    float uNoiseIntensity;
    int uNoiseDropoff;
    
    // Beryl Compat
    float uBerylFogFactor;
    float uBerylLightIntensity;
    float uBerylNightMultiplier;
    float uBerylLightVisibility;
    float uBerylMinAmbientLight;
    float uBerylAmbientLightFactor;
    vec3 uBerylLightDir;
    vec3 uBerylFogColor;
    vec3 uBerylLightColor;
    vec3 uBerylSkyColor;
    vec3 uBerylUpVector;
};

// ==================== //
// Beryl Compat Functions 
// ==================== //

#if defined(BERYL_COMPAT)
void atmospheric_fog(inout vec4 color, vec3 fragPos, float vertexDistance) {
    vec3 fragDir = normalize(fragPos);
    float fogAmount = 1.0 - exp(-vertexDistance * uBerylFogFactor);

    vec3 fc;
    if (uBerylLightIntensity > 0.0) {
        float SdotFd = max(dot(fragDir, uBerylLightDir), 0.0);
        fc = mix(uBerylFogColor,
                   uBerylLightColor * 0.5 * uBerylFogColor,
                   pow(SdotFd, 8.0));
    } else {
        fc = uBerylFogColor;
    }

    color.rgb = mix(color.rgb, fc, fogAmount);
}

vec3 computeBerylSkyColor(vec3 fragDir, vec3 lightDir, vec3 skyColorBase, vec3 fogColorBase, float nightFactor, float roUp, float soUp, float lightVis, vec3 lightColor) {
    vec3 skyColor = mix(fogColorBase, skyColorBase, roUp);

    float sunsetFactor = lightVis * (1.0 - nightFactor);
    vec3 sunsetColor = mix(skyColor, vec3(0.9, 0.2, 0.1), sunsetFactor);

    roUp = max(roUp, 0.0);
    float f1 = 1.0 - 0.1 /(5.0 * pow(roUp, 2.0) + 0.1);
    skyColor = mix(fogColorBase, skyColorBase, f1);

    float m1 = (1.0 - soUp);

    float FdotL = max(dot(fragDir, lightDir), 0.0);
    float m2 = pow(FdotL, 2.0);
    m2 *= pow(1.0 - roUp, 4.0);
    m2 *= m1;
    
    skyColor = mix(skyColor, sunsetColor, m2);
    return skyColor;
}

#endif


// ==================== //
//    Noise functions    //
// ==================== //

// Integer hash — no trig, faster than sin-based PRNG on GPU
uint ihash(uint x) { x += x << 10u; x ^= x >> 6u; x += x << 3u; x ^= x >> 11u; x += x << 15u; return x; }
float rand(float co) { return float(ihash(floatBitsToUint(co))) / 4294967295.0; }
float rand(vec2 co) { return float(ihash(ihash(floatBitsToUint(co.x)) ^ floatBitsToUint(co.y))) / 4294967295.0; }
float rand(vec3 co) { return float(ihash(ihash(ihash(floatBitsToUint(co.x)) ^ floatBitsToUint(co.y)) ^ floatBitsToUint(co.z))) / 4294967295.0; }

vec3 quantize(vec3 val, int stepSize)
{
    return floor(val * stepSize) / stepSize;
}

void applyNoise(inout vec4 frag, const in float viewDist)
{
    vec3 vertexNormal = normalize(cross(dFdy(vPos.xyz), dFdx(vPos.xyz)));
    vec3 fixedVPos = vPos.xyz + vertexNormal * 0.001;

    float noiseAmplification = uNoiseIntensity;
    float lum = (frag.r + frag.g + frag.b) / 3.0;
    noiseAmplification = (1.0 - pow(lum * 2.0 - 1.0, 2.0)) * noiseAmplification;
    noiseAmplification *= frag.a;

    float randomValue = rand(quantize(fixedVPos, uNoiseSteps))
        * 2.0 * noiseAmplification - noiseAmplification;

    vec3 newCol = frag.rgb + (1.0 - frag.rgb) * randomValue;
    newCol = clamp(newCol, 0.0, 1.0);

    if (uNoiseDropoff != 0) {
        float distF = min(viewDist / float(uNoiseDropoff), 1.0);
        newCol = mix(newCol, frag.rgb, distF);
    }

    frag.rgb = newCol;
}


// ==================== //
//    Dither function    //
// ==================== //

/** Returns a normalized value between 0.0 and 1.0 */
float bayerMatrix4x4(vec2 st)
{
    int x = int(mod(st.x, 4.0));
    int y = int(mod(st.y, 4.0));

    float bayer4x4[16] = float[16](
         0.0,  8.0,  2.0, 10.0,
        12.0,  4.0, 14.0,  6.0,
         3.0, 11.0,  1.0,  9.0,
        15.0,  7.0, 13.0,  5.0
    );

    int index = y * 4 + x;
    return bayer4x4[index] / 16.0;
}


// ==================== //
//         Main         //
// ==================== //

void main()
{
    fragColor = vertexColor;

    float viewDist = length(vertexWorldPos);

    // Fade/clip based on distance
    if (uDitherDhRendering != 0)
    {
        float worldNoise = bayerMatrix4x4(gl_FragCoord.xy);
        worldNoise += 0.001;

        float fadeStep = smoothstep(uClipDistance, uClipDistance * 1.5, viewDist);
        if (fadeStep <= worldNoise)
        {
            discard;
        }
    }

    // Apply noise
    if (uNoiseEnabled != 0)
    {
        applyNoise(fragColor, viewDist);
    }
    
#if defined(BERYL_COMPAT)
    // -------------------------------------------------------------
    // DH to Beryl Analytical Lighting Replication
    
    // 1. Raw Albedo from DH vertex tint (bypassing Vanilla Lightmap in .vert)
    // MUST convert to linear HDR space! DH natively bakes LOD colors in sRGB. 
    // Feeding sRGB directly into Beryl's Linear PBR pipeline creates highly saturated,
    // overly "yellow/vibrant" midtones because the gamma curve is evaluated linearly.
    vec3 albedo = pow(fragColor.rgb, vec3(2.2));
    
    // 2. Diffuse shading: Assumes flat terrain plane matching Beryl foliage closely.
    // NdotL = dot(UpVector, L) * 0.8 inside Beryl foliage. We emulate a uniform upward bounce.
    float NdotL = max(dot(uBerylUpVector, uBerylLightDir) * 0.8, 0.0);
    
    // Sun radiance from Sky (uses Beryl's exact altitude fade)
    vec3 radiance = uBerylLightColor * clamp((dot(uBerylUpVector, uBerylLightDir) - 0.04) * 50.0, 0.0, 1.0);
    
    // 3. Replicate Beryl's exact diffuse calculation from lighting.glsl (LightingSphereGGX2)
    // Lo = (kD * albedo * 0.318309) * absorption * radiance * NdotL + specular;
    float absorption = 0.6; // The smoking gun that suffocates terrain exposure
    vec3 diffuse = (albedo * 0.318309) * absorption * radiance * NdotL;
    
    // 4. Replicate terrain.fsh ambient integration using true light levels!
    // -> terrain.vsh: lightY = max(lightY * AmbientLightFactor - NightFactor, MinAmbientLight);
    // Since DH LODs are universally exterior surface terrain blocks, their physical 
    // SkyLight exposure base (UV2.y) is ALWAYS 1.0. We dynamically apply Beryl's Ambient and Night 
    // factors to perfectly align the ambient falloff with the local sky gradient at midnight.
    float beryl_lightY = 1.0 * uBerylAmbientLightFactor;
    float stableSkyLight = max(beryl_lightY - uBerylNightMultiplier, uBerylMinAmbientLight); 
    
    fragColor.rgb = diffuse + albedo * (0.3 * stableSkyLight + vLightLevels.x * vec3(1.0, 0.7, 0.5));
    
    // Beryl's final additive upward scattered illumination
    fragColor.rgb += radiance * 0.05 * albedo;
    // -------------------------------------------------------------
    
    if (fragColor.a < 0.99) {
        // Compute water reflection
        vec3 normal = vec3(0.0, 1.0, 0.0); // Simple horizontal plane for water reflection
        vec3 viewDir = normalize(vertexWorldPos);
        vec3 reflectedDir = reflect(viewDir, normal);
        
        float roUp = dot(reflectedDir, vec3(0.0, 1.0, 0.0));
        float soUp = max(dot(vec3(0.0, 1.0, 0.0), uBerylLightDir), 0.0);
        
        vec3 reflectedSkyColor = computeBerylSkyColor(reflectedDir, uBerylLightDir, uBerylSkyColor, uBerylFogColor, uBerylNightMultiplier, roUp, soUp, uBerylLightVisibility, uBerylLightColor);
        
        // Blend reflection heavily on grazing angles (Fresnel approximation)
        float fresnel = 1.0 - max(dot(-viewDir, normal), 0.0);
        fresnel = pow(fresnel, 5.0);
        float reflectTerm = mix(0.1, 0.9, fresnel);
        
        fragColor.rgb = mix(fragColor.rgb, reflectedSkyColor, reflectTerm);
        // Ensure water remains semi-opaque relative to its original DH alpha
        fragColor.a = max(fragColor.a, reflectTerm);
    }

    atmospheric_fog(fragColor, vertexWorldPos, viewDist);
#endif
}
