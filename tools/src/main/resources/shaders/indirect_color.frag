#version 460 core

in vec3 vColor;
in vec3 vNormal;
in vec3 vFragPos;
flat in int vMirrored;

out vec4 FragColor;

// Lighting uniform block (matches existing LightingBlock at binding 1)
layout(std140) uniform LightingBlock {
    vec3 ambientLight;
    vec3 lightDirection;
    vec3 lightColor;
};

void main() {
    // Flip normal for back faces (two-sided lighting)
    // For mirrored objects, the winding order is reversed, so gl_FrontFacing is inverted
    // We need to account for this to get correct lighting
    vec3 norm = normalize(vNormal);
    bool isFrontFace = gl_FrontFacing;
    if (vMirrored == 1) {
        // For mirrored objects, the notion of front/back is reversed
        isFrontFace = !isFrontFace;
    }
    if (!isFrontFace) {
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
