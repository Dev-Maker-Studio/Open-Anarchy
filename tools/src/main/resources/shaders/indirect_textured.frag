#version 460 core

in vec3 vColor;
in vec2 vTexCoord;
in vec3 vNormal;
in vec3 vFragPos;
flat in int vMirrored;
flat in int vTextureLayer;
flat in int vTextureArray;

in float vViewDist;

out vec4 FragColor;

const int MAX_TEXTURE_ARRAYS = 4;
uniform sampler2DArray textureArrays[MAX_TEXTURE_ARRAYS];

vec4 sampleMaterial(vec3 uvw) {
    if (vTextureArray <= 0) return texture(textureArrays[0], uvw);
    if (vTextureArray == 1) return texture(textureArrays[1], uvw);
    if (vTextureArray == 2) return texture(textureArrays[2], uvw);
    return texture(textureArrays[3], uvw);
}

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
    vec4 texColor = sampleMaterial(vec3(vTexCoord, float(vTextureLayer)));
    float alpha = texColor.a;
    if (alpha < 0.01) discard;
    vec3 baseColor = texColor.rgb * vColor;

    vec3 norm = normalize(vNormal);
    if (!gl_FrontFacing) {
        norm = -norm;
    }

    vec3 result = applySunLight(baseColor, norm);
    FragColor = vec4(applyFog(result, vViewDist), alpha);
}
