#version 460 core

in vec3 vColor;
in vec2 vTexCoord;
in vec3 vNormal;
in vec3 vFragPos;
flat in int vMirrored;
flat in int vTextureLayer;

out vec4 FragColor;

uniform sampler2DArray textureArray;

// Lighting uniform block (matches existing LightingBlock at binding 1)
layout(std140) uniform LightingBlock {
    vec3 ambientLight;
    vec3 lightDirection;
    vec3 lightColor;
};

void main() {
    vec4 texColor = texture(textureArray, vec3(vTexCoord, float(vTextureLayer)));
    float alpha = texColor.a;
    if (alpha < 0.01) discard;
    // Multiplicative blend: texture color modulated by vertex color
    // Matches RS client: color = texColor * vColor
    vec3 baseColor = texColor.rgb * vColor;

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

    // Ambient lighting
    vec3 ambient = ambientLight * baseColor;

    // Diffuse lighting
    vec3 lightDir = normalize(-lightDirection);
    float diff = max(dot(norm, lightDir), 0.0);
    vec3 diffuse = diff * lightColor * baseColor;

    vec3 result = ambient + diffuse;
    FragColor = vec4(result, alpha);
}
