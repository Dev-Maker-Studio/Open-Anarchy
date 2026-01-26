#version 330 core

in vec3 vColor;
in vec3 vNormal;
in vec3 vFragPos;

out vec4 FragColor;

uniform vec3 ambientLight;
uniform vec3 lightDirection;
uniform vec3 lightColor;

void main() {
    // Ambient
    vec3 ambient = ambientLight * vColor;

    // Diffuse
    vec3 norm = normalize(vNormal);
    vec3 lightDir = normalize(-lightDirection);
    float diff = max(dot(norm, lightDir), 0.0);
    vec3 diffuse = diff * lightColor * vColor;

    vec3 result = ambient + diffuse;
    FragColor = vec4(result, 1.0);
}
