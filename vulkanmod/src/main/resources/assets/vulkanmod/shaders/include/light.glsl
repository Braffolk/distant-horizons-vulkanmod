//light.glsl
//#pragma once
const float MINECRAFT_LIGHT_POWER = (0.6);
const float MINECRAFT_AMBIENT_LIGHT = (0.4);

vec4 minecraft_sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texelFetch(lightMap, bitfieldExtract(uv, 4, 8), 0);
    //return texture(lightMap, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

vec4 sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texelFetch(lightMap, bitfieldExtract(uv, 4, 8), 0);
}

vec4 sample_lightmap2(sampler2D lightMap, uint uv) {
    const ivec2 lm = ivec2(bitfieldExtract(uv, 4, 4), bitfieldExtract(uv, 12, 4));
    //    const ivec2 lm = ivec2(uv >> 12, (uv >> 4) & 0xF);
    return texelFetch(lightMap, lm, 0);
}

vec2 minecraft_compute_light(vec3 lightDir0, vec3 lightDir1, vec3 normal) {
    return vec2(dot(lightDir0, normal), dot(lightDir1, normal));
}

vec4 minecraft_mix_light_separate(vec2 light, vec4 color) {
    vec2 lightValue = max(vec2(0.0), light);
    float lightAccum = min(1.0, fma((lightValue.x + lightValue.y), MINECRAFT_LIGHT_POWER, MINECRAFT_AMBIENT_LIGHT));
    return vec4(color.rgb * lightAccum, color.a);
}

vec4 minecraft_mix_light(vec3 lightDir0, vec3 lightDir1, vec3 normal, vec4 color) {
    vec2 light = minecraft_compute_light(lightDir0, lightDir1, normal);
    return minecraft_mix_light_separate(light, color);
}
