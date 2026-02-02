#version 330 core

in vec3 vColor;
in vec3 vNormal;
in vec3 vFragPos;

out vec4 FragColor;

// Lighting uniform block (kept for compatibility, but not used for terrain)
// Terrain colors are pre-lit on the CPU using the RS client's HSL lighting algorithm.
// Applying shader lighting on top would cause double-darkening.
layout(std140) uniform LightingBlock {
    vec3 ambientLight;
    vec3 lightDirection;
    vec3 lightColor;
};

void main() {
    // Terrain vertex colors are already lit using the RS client's lighting algorithm
    // which modifies HSL lightness based on surface normals BEFORE RGB conversion.
    // We just pass through the pre-lit color directly.
    FragColor = vec4(vColor, 1.0);
}
