#version 330 core

in vec3 vColor;
in vec3 vNormal;
in vec3 vFragPos;

out vec4 FragColor;

// Lighting uniform block
layout(std140) uniform LightingBlock {
    vec3 ambientLight;
    vec3 lightDirection;
    vec3 lightColor;
};

void main() {
    // Flip normal for back faces (two-sided lighting)
    vec3 norm = normalize(vNormal);
    if (!gl_FrontFacing) {
        norm = -norm;
    }

    // Ambient
    vec3 ambient = ambientLight * vColor;

    // Diffuse
    vec3 lightDir = normalize(-lightDirection);
    float diff = max(dot(norm, lightDir), 0.0);
    vec3 diffuse = diff * lightColor * vColor;

    vec3 result = ambient + diffuse;
    FragColor = vec4(result, 1.0);
}
