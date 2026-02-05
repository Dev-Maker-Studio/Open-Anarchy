#version 330 core

in vec3 vColor;
in vec2 vTexCoord;
in vec3 vNormal;
in vec3 vFragPos;
flat in int vMirrored;

out vec4 FragColor;

uniform sampler2D textureSampler;
uniform vec3 ambientLight;
uniform vec3 lightDirection;
uniform vec3 lightColor;
uniform bool useTexture;

void main() {
    vec3 baseColor;
    float alpha = 1.0;

    if (useTexture) {
        vec4 texColor = texture(textureSampler, vTexCoord);
        alpha = texColor.a;
        if (alpha < 0.01) discard;
        // Multiplicative blend: texture color modulated by vertex color
        // Matches RS client: color = texColor * vColor
        baseColor = texColor.rgb * vColor;
    } else {
        baseColor = vColor;
    }

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
