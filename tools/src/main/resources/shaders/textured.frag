#version 330 core

in vec3 vColor;
in vec2 vTexCoord;
in vec3 vNormal;
in vec3 vFragPos;

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
        baseColor = texColor.rgb * vColor; // Blend texture with vertex color
        alpha = texColor.a;
        if (alpha < 0.1) discard; // Discard nearly transparent pixels
    } else {
        baseColor = vColor;
    }

    // Ambient lighting
    vec3 ambient = ambientLight * baseColor;

    // Diffuse lighting
    vec3 norm = normalize(vNormal);
    vec3 lightDir = normalize(-lightDirection);
    float diff = max(dot(norm, lightDir), 0.0);
    vec3 diffuse = diff * lightColor * baseColor;

    vec3 result = ambient + diffuse;
    FragColor = vec4(result, alpha);
}
