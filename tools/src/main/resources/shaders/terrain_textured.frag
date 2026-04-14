#version 330 core

in vec3 vColor;
in vec2 vTexCoord;
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
    float alpha = texColor.a;

    // Discard fully transparent fragments
    if (alpha < 0.01) {
        discard;
    }

    FragColor = vec4(baseColor, alpha);
}
