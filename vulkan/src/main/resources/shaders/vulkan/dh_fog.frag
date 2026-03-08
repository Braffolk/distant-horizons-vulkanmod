#version 450

layout(location = 0) in vec2 TexCoord;
layout(location = 0) out vec4 fragColor;

// UBO at binding 0
layout(std140, binding = 0) uniform FogUBO {
    mat4 uInvMvmProj;      // inverse model-view-projection
    vec4 uFogColor;         // fog color (RGBA)
    float uFogScale;        // 1.0 / lodDrawDistance
    float uFogVerticalScale;// 1.0 / maxWorldHeight
    int uFogDebugMode;      // 0 = normal, 1 = full fog, 7 = depth debug
    int uFogFalloffType;    // 0=linear, 1=exp, 2=exp²

    // far fog config
    float uFarFogStart;
    float uFarFogLength;
    float uFarFogMin;
    float uFarFogRange;
    float uFarFogDensity;

    // height fog config
    float uHeightFogStart;
    float uHeightFogLength;
    float uHeightFogMin;
    float uHeightFogRange;
    float uHeightFogDensity;

    // height fog flags (packed as ints since GLSL has no bool in UBO)
    int uHeightFogEnabled;
    int uHeightFogFalloffType;
    int uHeightBasedOnCamera;
    float uHeightFogBaseHeight;
    int uHeightFogAppliesUp;
    int uHeightFogAppliesDown;
    int uUseSphericalFog;
    int uHeightFogMixingMode;
    float uCameraBlockYPos;
};

// Depth texture at binding 1
layout(binding = 1) uniform sampler2D uDepthMap;


//====================//
// method definitions //
//====================//

vec3 calcViewPosition(float fragmentDepth);
float getFarFogThickness(float dist);
float getHeightFogThickness(float dist);
float calculateHeightFogDepth(float worldYPos);
float mixFogThickness(float far, float height);

float linearFog(float worldDist, float fogStart, float fogLength, float fogMin, float fogRange);
float exponentialFog(float x, float fogStart, float fogLength, float fogMin, float fogRange, float fogDensity);
float exponentialSquaredFog(float x, float fogStart, float fogLength, float fogMin, float fogRange, float fogDensity);


//======//
// main //
//======//

void main()
{
    float fragmentDepth = textureLod(uDepthMap, TexCoord, 0).r;
    fragColor = vec4(uFogColor.rgb, 0.0);

    // depth of 1.0 means nothing was drawn — skip sky
    if (fragmentDepth < 1.0)
    {
        int fogDebugMode = uFogDebugMode;
        if (fogDebugMode == 0)
        {
            vec3 vertexWorldPos = calcViewPosition(fragmentDepth);

            float horizontalWorldDistance = length(vertexWorldPos.xz) * uFogScale;
            float worldDistance = length(vertexWorldPos.xyz) * uFogScale;
            float activeDistance = (uUseSphericalFog != 0) ? worldDistance : horizontalWorldDistance;

            // far fog
            float farFogThickness = getFarFogThickness(activeDistance);

            // height fog
            float heightFogDepth = calculateHeightFogDepth(vertexWorldPos.y);
            float heightFogThickness = getHeightFogThickness(heightFogDepth);

            // combined fog
            float mixedFogThickness = mixFogThickness(farFogThickness, heightFogThickness);
            fragColor.a = clamp(mixedFogThickness, 0.0, 1.0);
        }
        else if (fogDebugMode == 1)
        {
            fragColor.a = 1.0;
        }
        else
        {
            float depthValue = textureLod(uDepthMap, TexCoord, 0).r;
            fragColor.rgb = vec3(depthValue);
            fragColor.a = 1.0;
        }
    }
}


//================//
// helper methods //
//================//

vec3 calcViewPosition(float fragmentDepth)
{
    vec4 ndc = vec4(TexCoord.xy, fragmentDepth, 1.0);
    ndc.xyz = ndc.xyz * 2.0 - 1.0;
    vec4 eyeCoord = uInvMvmProj * ndc;
    return eyeCoord.xyz / eyeCoord.w;
}


//=========//
// far fog //
//=========//

float getFarFogThickness(float dist)
{
    if (uFogFalloffType == 0) // LINEAR
    {
        return linearFog(dist, uFarFogStart, uFarFogLength, uFarFogMin, uFarFogRange);
    }
    else if (uFogFalloffType == 1) // EXPONENTIAL
    {
        return exponentialFog(dist, uFarFogStart, uFarFogLength, uFarFogMin, uFarFogRange, uFarFogDensity);
    }
    else // EXPONENTIAL_SQUARED
    {
        return exponentialSquaredFog(dist, uFarFogStart, uFarFogLength, uFarFogMin, uFarFogRange, uFarFogDensity);
    }
}

float getHeightFogThickness(float dist)
{
    if (uHeightFogEnabled == 0)
    {
        return 0.0;
    }

    if (uHeightFogFalloffType == 0) // LINEAR
    {
        return linearFog(dist, uHeightFogStart, uHeightFogLength, uHeightFogMin, uHeightFogRange);
    }
    else if (uHeightFogFalloffType == 1) // EXPONENTIAL
    {
        return exponentialFog(dist, uHeightFogStart, uHeightFogLength, uHeightFogMin, uHeightFogRange, uHeightFogDensity);
    }
    else // EXPONENTIAL_SQUARED
    {
        return exponentialSquaredFog(dist, uHeightFogStart, uHeightFogLength, uHeightFogMin, uHeightFogRange, uHeightFogDensity);
    }
}

float linearFog(float worldDist, float fogStart, float fogLength, float fogMin, float fogRange)
{
    worldDist = (worldDist - fogStart) / fogLength;
    worldDist = clamp(worldDist, 0.0, 1.0);
    return fogMin + fogRange * worldDist;
}

float exponentialFog(
    float x, float fogStart, float fogLength,
    float fogMin, float fogRange, float fogDensity)
{
    x = max((x - fogStart) / fogLength, 0.0) * fogDensity;
    return fogMin + fogRange - fogRange / exp(x);
}

float exponentialSquaredFog(
    float x, float fogStart, float fogLength,
    float fogMin, float fogRange, float fogDensity)
{
    x = max((x - fogStart) / fogLength, 0.0) * fogDensity;
    return fogMin + fogRange - fogRange / exp(x * x);
}


//============//
// height fog //
//============//

float calculateHeightFogDepth(float worldYPos)
{
    if (uHeightFogEnabled == 0)
    {
        return 0.0;
    }

    if (uHeightBasedOnCamera == 0)
    {
        worldYPos -= (uHeightFogBaseHeight - uCameraBlockYPos);
    }

    if (uHeightFogAppliesDown != 0 && uHeightFogAppliesUp != 0)
    {
        return abs(worldYPos) * uFogVerticalScale;
    }
    else if (uHeightFogAppliesDown != 0)
    {
        return -worldYPos * uFogVerticalScale;
    }
    else if (uHeightFogAppliesUp != 0)
    {
        return worldYPos * uFogVerticalScale;
    }
    else
    {
        return 0.0;
    }
}

float mixFogThickness(float far, float height)
{
    switch (uHeightFogMixingMode)
    {
        case 0: // BASIC
        case 1: // IGNORE_HEIGHT
        return far;

        case 2: // MAX
        return max(far, height);

        case 3: // ADDITION
        return (far + height);

        case 4: // MULTIPLY
        return far * height;

        case 5: // INVERSE_MULTIPLY
        return (1.0 - (1.0 - far) * (1.0 - height));

        case 6: // LIMITED_ADDITION
        return (far + max(far, height));

        case 7: // MULTIPLY_ADDITION
        return (far + far * height);

        case 8: // INVERSE_MULTIPLY_ADDITION
        return (far + 1.0 - (1.0 - far) * (1.0 - height));

        case 9: // AVERAGE
        return (far * 0.5 + height * 0.5);
    }

    return far;
}
