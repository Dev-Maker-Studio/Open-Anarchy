#version 330 core

in vec3 vColor;
in vec2 vTexCoord;
in float vAlpha;
in vec3 vNormal;
in vec3 vFragPos;

out vec4 FragColor;

uniform sampler2D textureSampler;

void main() {
    vec4 texColor = texture(textureSampler, vTexCoord);

    // Terrain vertex colors are already lit using the RS client's lighting algorithm
    // which modifies HSL lightness based on surface normals BEFORE RGB conversion.
    // We multiply texture by pre-lit vertex color and pass through directly.
    vec3 baseColor = texColor.rgb * vColor;

    // Combine texture alpha with per-vertex alpha from multi-pass blending.
    // vAlpha controls the blend strength of this layer over the opaque base layer.
    float alpha = texColor.a * vAlpha;

    // Discard fully transparent fragments
    if (alpha < 0.01) {
        discard;
    }

    FragColor = vec4(baseColor, alpha);
}
