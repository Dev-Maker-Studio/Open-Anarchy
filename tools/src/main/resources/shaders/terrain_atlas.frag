#version 330 core

in vec3 vColor;
in vec2 vTexCoord;
in vec3 vNormal;
in vec3 vFragPos;
in vec4 vAtlasTransform;

in float vViewDist;

out vec4 FragColor;

uniform sampler2D atlasTexture;

layout(std140) uniform EnvironmentBlock {
    vec4 sunDirection;
    vec4 sunColour;
    vec4 sunTerms;
    vec4 fogColour;
    vec4 fogRange;
};

vec3 applySunLight(vec3 base, vec3 normal) {
    if (sunTerms.w < 0.5) {
        return base;
    }
    float ndl = dot(normal, sunDirection.xyz);
    vec3 lit = sunTerms.x * sunColour.rgb
             + max(ndl, 0.0) * sunTerms.y * sunColour.rgb
             - max(-ndl, 0.0) * sunTerms.z * sunColour.rgb;
    return base * max(lit, vec3(0.0));
}

vec3 applyFog(vec3 colour, float viewDistance) {
    if (fogColour.w < 0.5) {
        return colour;
    }
    float factor = clamp((fogRange.y - viewDistance) * fogRange.z, 0.0, 1.0);
    return mix(fogColour.rgb, colour, factor);
}

void main() {
    vec2 atlasScale = vAtlasTransform.zw;
    vec2 dx = dFdx(vTexCoord) * atlasScale;
    vec2 dy = dFdy(vTexCoord) * atlasScale;

    vec2 tiledUV = fract(vTexCoord);
    vec2 atlasUV = vAtlasTransform.xy + tiledUV * atlasScale;
    vec4 texColor = textureGrad(atlasTexture, atlasUV, dx, dy);

    vec3 baseColor = applySunLight(texColor.rgb * vColor, normalize(vNormal));
    float alpha = texColor.a;

    if (alpha < 0.01) {
        discard;
    }

    FragColor = vec4(applyFog(baseColor, vViewDist), alpha);
}
