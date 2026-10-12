#version 330 core

in vec2 fragTexCoord;
in vec4 fragColor;

out vec4 outColor;

uniform sampler2D uiTexture;
uniform bool useTexture;

void main() {
    vec4 colour = fragColor;
    if (useTexture) {
        colour *= texture(uiTexture, fragTexCoord);
    }
    if (colour.a <= 0.0) {
        discard;
    }
    outColor = colour;
}
