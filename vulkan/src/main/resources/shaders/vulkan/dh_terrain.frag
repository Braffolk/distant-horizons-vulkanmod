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

// Output
layout(location = 0) out vec4 fragColor;

// Uniforms — shared with vertex shader
layout(set = 0, binding = 0) uniform DhUniforms {
    mat4 uCombinedMatrix;
    vec3 uModelOffset;
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
};


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
}
