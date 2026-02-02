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

void main() {
    vec4 texColor = texture(textureSampler, vTexCoord);
    vec3 baseColor = texColor.rgb * vColor; // Blend texture with vertex color
    float alpha = texColor.a;

    // Discard transparent fragments (cutout transparency for leaves, etc.)
    if (alpha < 0.5) discard;

    // Flip normal for back faces (two-sided lighting)
    vec3 norm = normalize(vNormal);
    if (!gl_FrontFacing) {
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
