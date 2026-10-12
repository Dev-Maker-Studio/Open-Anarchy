#version 330 core

in vec3 vColor;
in vec2 vTexCoord;
in float vAlpha;
in vec3 vNormal;
in vec3 vFragPos;

in float vViewDist;

out vec4 FragColor;

uniform sampler2D textureSampler;

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
    vec4 texColor = texture(textureSampler, vTexCoord);

    vec3 baseColor = applySunLight(texColor.rgb * vColor, normalize(vNormal));

    float alpha = texColor.a * vAlpha;

    if (alpha < 0.01) {
        discard;
    }

    FragColor = vec4(applyFog(baseColor, vViewDist), alpha);
}
