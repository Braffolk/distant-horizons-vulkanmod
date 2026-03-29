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
    mat4 uInvViewMatrix;
    
    // Beryl Compat
    float uBerylFogFactor;
    float uBerylFogFactorRaw;  // Original unscaled fog factor for close-range atmospheric haze
    float uBerylFogEnd;
    float uBerylFogStart;
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
vec3 computeBerylSkyColor(vec3 fragDir, vec3 lightDir, vec3 skyColorBase, vec3 fogColorBase, float nightFactor, float roUp, float soUp, float lightVis, vec3 lightColor);

// Replicated from assets/beryl/shaders/new/include/fog2.glsl:36
void atmospheric_fog(inout vec4 color, vec3 fragPos, float vertexDistance, float fogFactor, vec3 worldLightDir) {
    vec3 fragDir = normalize(fragPos);
    float fogAmount = 1.0 - exp(-vertexDistance * fogFactor);

    vec3 fogColor;
    if (uBerylLightIntensity > 0.0) {
        float SdotFd = max(dot(fragDir, worldLightDir), 0.0);
        fogColor = mix( uBerylFogColor,
                        uBerylLightColor * 0.5 * uBerylFogColor,
                        pow(SdotFd,8.0) );
    } else {
        fogColor = uBerylFogColor;
    }
    color.rgb = mix(color.rgb, fogColor, fogAmount);
}

// Replicated from assets/beryl/shaders/new/include/fog2.glsl:11
vec4 fog(vec4 color, float fragDistance, float fogEnd, vec4 fogColor) {
    float fogAmt = smoothstep(0.8*fogEnd, 1.0*fogEnd, fragDistance);
    color = mix(color, fogColor, fogAmt);
    return color;
}

// Replicated from assets/beryl/shaders/new/include/lighting.glsl:3
vec3 getSkyColor(vec3 fragDir, vec3 LightDir, vec3 SkyColorBase, vec3 FogColorBase, float NightMultiplier, float RoUp, float SoUp, float LightVisibility, vec3 LightColor) {
    vec3 skyColor = mix(FogColorBase, SkyColorBase, RoUp);

    float sunsetFactor = LightVisibility * (1.0 - NightMultiplier);
    vec3 sunsetColor = mix(skyColor, vec3(0.9, 0.2, 0.1), sunsetFactor);

    RoUp = max(RoUp, 0.0);
    float f1 = 1.0 - 0.1 /(5.0 * pow(RoUp, 2.0) + 0.1);
    skyColor = mix(FogColorBase, SkyColorBase, f1);

    float m1 = (1.0 - SoUp);

    float FdotL = max(dot(fragDir, LightDir), 0.0);
    float m2 = pow(FdotL, 2.0);
    m2 *= pow(1.0 - RoUp, 4.0);
    m2 *= m1;
    
    skyColor = mix(skyColor, sunsetColor, m2);
    return skyColor;
}

// Replicated from assets/beryl/shaders/new/include/lighting.glsl:40
vec3 getSunColor(vec3 fragDir, vec3 LightDir, vec3 LightColor) {
    float FdotL = max(dot(fragDir, LightDir), 0.0);
    float m = smoothstep(0.999, 0.9995, FdotL);
    vec3 color = 10.0 * mix(vec3(0.0), LightColor, m);
    color += LightColor * 2.0 * (0.05 / ((1.0 - FdotL) * 200.0 + 1.0));
    return color;
}

// Replicated from assets/beryl/shaders/new/include/lighting.glsl:56
vec3 getMoonColor(vec3 fragDir, vec3 LightDir, vec3 LightColor) {
    float FdotL = max(dot(fragDir, LightDir), 0.0);
    vec3 color = LightColor * (0.05 / ((1.0 - FdotL) * 200.0 + 1.0));
    return color;
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
// Beryl Analytical BRDF//
// ==================== //

#if defined(BERYL_COMPAT)

// Replicated from assets/beryl/shaders/new/include/lighting.glsl:1
#define PI 3.14159265359

// Replicated from assets/beryl/shaders/new/include/lighting.glsl:63
float saturateXZ(float f) {
    return clamp(f, 0.0, 1.0);
}

// Replicated from assets/beryl/shaders/new/include/lighting.glsl:120:GeometrySchlickGGX
float GeometrySchlickGGX(float NdotV, float roughness)
{
    float r = (roughness + 1.0);
    float k = (r*r) * 0.125;

    float num   = NdotV;
    float denom = NdotV * (1.0 - k) + k;

    return num / denom;
}

// Replicated from assets/beryl/shaders/new/include/lighting.glsl:141:fresnelSchlick
vec3 fresnelSchlick(float cosTheta, vec3 F0)
{
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

// Replicated exactly from assets/beryl/shaders/new/include/lighting.glsl:294:LightingSphereGGX2
vec3 LightingSphereGGX2(vec3 V, vec3 N, vec3 L, vec3 albedo, vec3 radiance, vec3 F0, float roughness, float metallic, int lightingType) {
    vec3 R = reflect(-V, N);
    vec3 centerToRay = (R - L);
    float radius = 0.025;
    float d = length(centerToRay);
    vec3 O = centerToRay * saturateXZ(radius / d);
    L = L + O;
    L = normalize(L);

    vec3 Lo = vec3(0.0);
    vec3 H = normalize(V + L);

    float a      = roughness*roughness;
    float a2     = a*a;
    float NdotH  = max(dot(N, H), 0.0);
    float NdotH2 = NdotH*NdotH;

    float num   = a2;
    float denom = (NdotH2 * (a2 - 1.0) + 1.0);
    denom = PI * denom * denom;
    float NDF = num / denom;

    float NdotV = max(dot(N, V), 0.0);
    float NdotL = max(dot(N, L), 0.0);

    // Modified from exact source line 332: UpVector -> uBerylUpVector for UBO compat
    NdotL = lightingType == 1 ? (dot(uBerylUpVector, L) * 0.8) : NdotL;

    float ggx2  = GeometrySchlickGGX(NdotV, roughness);
    float ggx1  = GeometrySchlickGGX(NdotL, roughness);
    float G = ggx1 * ggx2;

    vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);

    vec3 kS = F;
    vec3 kD = vec3(1.0) - kS;
    kD *= 1.0 - metallic;

    vec3 numerator    = NDF * G * F;
    float denominator = 4.0 * NdotV * NdotL + 0.0001;
    vec3 specular     = numerator / denominator;

    specular = min(specular * NdotL, 1.0) * radiance;

    const float absorption = 0.6;
    Lo = (kD * albedo * 0.31830988618) * absorption * radiance * NdotL + specular;

    return Lo;
}

#endif


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
    // 1. Raw Albedo 
    // Optifine natively binds Block Atlases in sRGB mode, meaning GPU hardware automatically 
    // darkens texture() queries to Linear Space before terrain.fsh receives them!
    // DH pipes colors manually as vertex floats, bypassing hardware linear conversion.
    // WE MUST manually linearize them to prevent blinding glow when fed into GGX math!
    vec3 albedo = pow(fragColor.rgb, vec3(2.2));
    
    // 2. Light Vectors & Normals
    vec3 viewDir = normalize(-vertexWorldPos); // Replicated from assets/beryl/shaders/new/terrain/terrain.fsh:64
    
    // Object geometry rendering is evaluated in World Space to respect strict 3D physical interactions
    vec3 worldLightDir = normalize(mat3(uInvViewMatrix) * uBerylLightDir);
    // Note: We use true physical Zenith for PBR since it determines sun brightness relative to ground!
    vec3 zenithUP = vec3(0.0, 1.0, 0.0);
    
    vec3 dX = dFdx(vertexWorldPos);
    vec3 dY = dFdy(vertexWorldPos);
    vec3 geometricNormal = normalize(cross(dX, dY));
    if (dot(geometricNormal, vertexWorldPos) > 0.0) {
        geometricNormal = -geometricNormal;
    }
    
    // 3. Exact Normal Reconstruction (Quantized to Minecraft block-faces to ensure identical contrast dropping)
    vec3 absN = abs(geometricNormal);
    vec3 vertexNormal;
    if (absN.x > absN.y && absN.x > absN.z) {
        vertexNormal = vec3(sign(geometricNormal.x), 0.0, 0.0);
    } else if (absN.y > absN.x && absN.y > absN.z) {
        vertexNormal = vec3(0.0, sign(geometricNormal.y), 0.0);
    } else {
        vertexNormal = vec3(0.0, 0.0, sign(geometricNormal.z));
    }
    
    // 4. Exact Material Defaults
    vec3 F0 = vec3(0.04);
    float roughness = 1.0;
    float metallic = 0.0;
    
    // 5. Exact Radiance Calculation
    vec3 radiance = uBerylLightColor;
    float sunGate = saturateXZ((dot(zenithUP, worldLightDir) - 0.04) * 50.0);
    radiance *= sunGate;
    
    // 6. Evaluate BRDF (lightingType=0 Solid)
    vec3 color1 = LightingSphereGGX2(viewDir, vertexNormal, worldLightDir, albedo, radiance, F0, roughness, metallic, 0);

    // 6.b Evaluate ambient `lightY`
    float beryl_lightY = 1.0 * uBerylAmbientLightFactor;
    float lightY = max(beryl_lightY - uBerylNightMultiplier, uBerylMinAmbientLight); 

    // 7. Final Composition 
    fragColor.rgb = (color1 * 1.0 * 1.0) + albedo * (0.3 * lightY + vLightLevels.x * vec3(1.0, 0.7, 0.5));
    
    // Strict block lighting (sun bounce upward reflection natively uses Zenith)
    float NdotU = max(dot(vertexNormal, zenithUP), 0.0);
    fragColor.rgb += radiance * 0.05 * NdotU * albedo * 1.0 * 1.0;
    
    if (fragColor.a < 0.99) {
        vec3 normal = vec3(0.0, 1.0, 0.0); 
        vec3 viewDirW = normalize(vertexWorldPos);
        vec3 reflectedDir = reflect(viewDirW, normal);
        
        // Water is a pure surface effect mapped against absolute world positions
        float roUp = dot(reflectedDir, vec3(0.0, 1.0, 0.0));
        float soUp = max(dot(vec3(0.0, 1.0, 0.0), worldLightDir), 0.0);
        
        vec3 reflectedSkyColor = getSkyColor(reflectedDir, worldLightDir, uBerylSkyColor, uBerylFogColor, uBerylNightMultiplier, roUp, soUp, uBerylLightVisibility, uBerylLightColor);
        
        // PBR specular sun/moon reflection on water — uses same LightingSphereGGX2
        // math as Beryl to produce the naturally elongated sun path. The half-vector
        // evaluation with low roughness creates the characteristic stretched highlight.
        {
            vec3 V = -viewDirW; // toward camera (Beryl convention)
            vec3 N = normal;    // (0, 1, 0)
            vec3 L = worldLightDir;
            
            // Sphere light approximation (Beryl radius = 0.025)
            vec3 R = reflect(-V, N);
            vec3 centerToRay = R - L;
            float sRadius = 0.025;
            float sDist = length(centerToRay);
            L = normalize(L + centerToRay * clamp(sRadius / sDist, 0.0, 1.0));
            
            vec3 H = normalize(V + L);
            const float waterRoughness = 0.1;
            float a = waterRoughness * waterRoughness;
            float a2 = a * a;
            float NdotH = max(dot(N, H), 0.0);
            float denomNDF = (NdotH * NdotH * (a2 - 1.0) + 1.0);
            float NDF = a2 / (3.14159265 * denomNDF * denomNDF);
            
            float NdotV = max(dot(N, V), 0.0);
            float NdotL = max(dot(N, L), 0.0);
            float r2 = (waterRoughness + 1.0);
            float k = (r2 * r2) * 0.125;
            float G1 = NdotV / (NdotV * (1.0 - k) + k);
            float G2 = NdotL / (NdotL * (1.0 - k) + k);
            float G = G1 * G2;
            
            vec3 F0_water = vec3(0.02);
            vec3 F = F0_water + (1.0 - F0_water) * pow(clamp(1.0 - max(dot(H, V), 0.0), 0.0, 1.0), 5.0);
            
            vec3 specNum = NDF * G * F;
            float specDen = 4.0 * NdotV * NdotL + 0.0001;
            vec3 specular = min(specNum / specDen * NdotL, 1.0);
            
            // Gate by radiance (same as Beryl's terrain: saturate((dot(up,L)-0.04)*50))
            vec3 waterRadiance = uBerylLightColor * clamp((dot(vec3(0.0, 1.0, 0.0), worldLightDir) - 0.04) * 50.0, 0.0, 1.0);
            reflectedSkyColor += specular * waterRadiance;
        }
        
        float fresnel = 1.0 - max(dot(-viewDirW, normal), 0.0);
        fresnel = pow(fresnel, 5.0);
        float reflectTerm = mix(0.1, 0.9, fresnel);
        fragColor.rgb = mix(fragColor.rgb, reflectedSkyColor, reflectTerm);
        fragColor.a = max(fragColor.a, reflectTerm);
    }

    // --- BERYL SCREEN-SPACE ATMOSPHERIC ANCHORING ---
    // Beryl natively evaluates fog2.glsl against VIEW SPACE constraints. 
    // Specifically, when it computes dot(fragDir, vec3(0,1,0)), it is taking the Screen-Relative Y 
    // coordinate to force the horizon gradient to perpetually intersect the user's crosshair natively!
    vec3 viewSpaceFragPos = (mat4(uInvViewMatrix) * vec4(vertexWorldPos, 1.0)).xyz; // Convert DH World back to local Beryl View Space
    vec3 viewSpaceFragDir = normalize(viewSpaceFragPos);
    
    // Atmospheric haze uses the SCALED fog factor — matching what Beryl terrain
    // actually receives (BerylAtmosphereOverride scales the global FogFactor supplier
    // to the DH LOD distance, so Beryl terrain sees the same reduced density).
    float fogAmount = 1.0 - exp(-viewDist * uBerylFogFactor);
    vec3 fogColorVol;
    if (uBerylLightIntensity > 0.0) {
        float SdotFd = max(dot(viewSpaceFragDir, uBerylLightDir), 0.0);
        fogColorVol = mix( uBerylFogColor, uBerylLightColor * 0.5 * uBerylFogColor, pow(SdotFd,8.0) );
    } else {
        fogColorVol = uBerylFogColor;
    }
    fragColor.rgb = mix(fragColor.rgb, fogColorVol, fogAmount);

    // View distance horizon glow uses rigorous Screen-Space Y mapping
    if (viewDist > 0.8 * uBerylFogEnd) {
        float RoUp = max(viewSpaceFragDir.y, 0.0); // Equivalent to dot(viewSpaceFragDir, vec3(0,1,0))
        float SoUp = max(uBerylLightDir.y, 0.0); // Equivalent to dot(vec3(0,1,0), uBerylLightDir)
        
        vec4 horizonSkyColor = vec4(getSkyColor(viewSpaceFragDir, uBerylLightDir, uBerylSkyColor, uBerylFogColor, uBerylNightMultiplier, RoUp, SoUp, uBerylLightVisibility, uBerylLightColor), 1.0);
        fragColor = fog(fragColor, viewDist, uBerylFogEnd, horizonSkyColor);
    }
    // Luminance-adaptive gamma lift for OPAQUE TERRAIN only (skip water).
    // Compensates for display-pipeline brightness gap at night.
    if (fragColor.a >= 0.99) {
        float lum = dot(fragColor.rgb, vec3(0.299, 0.587, 0.114));
        float gammaLift = mix(0.76, 1.0, smoothstep(0.0, 0.15, lum));
        fragColor.rgb = pow(max(fragColor.rgb, vec3(0.0)), vec3(gammaLift));
    }
#endif
}
