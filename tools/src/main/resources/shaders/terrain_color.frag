#version 330 core

in vec3 vColor;
in vec3 vNormal;
in vec3 vFragPos;

out vec4 FragColor;

void main() {
    // Terrain vertex colors are already lit using the RS client's lighting algorithm
    // which modifies HSL lightness based on surface normals BEFORE RGB conversion.
    // We just pass through the pre-lit color directly.
    FragColor = vec4(vColor, 1.0);
}
