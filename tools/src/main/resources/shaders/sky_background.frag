#version 330 core


out vec4 fragColor;

uniform vec2 skyViewport;
uniform vec2 skyOffset;
uniform int  skyMode;
uniform vec3 skyColor;
uniform vec3 skyTopColor;
uniform vec3 skyBottomColor;
uniform float skyAlpha;
uniform sampler2D skySampler;

void main() {
    if (skyMode == 0) {
        fragColor = vec4(skyColor, skyAlpha);
        return;
    }

    vec2 pixel = vec2(gl_FragCoord.x, skyViewport.y - gl_FragCoord.y);
    vec2 uv = (pixel - skyOffset) / skyViewport.y;

    if (skyMode == 1) {
        fragColor = vec4(texture(skySampler, uv).rgb, skyAlpha);
        return;
    }

    vec3 color;
    if (uv.y < 0.0) {
        color = skyTopColor;
    } else if (uv.y > 1.0) {
        color = skyBottomColor;
    } else {
        color = texture(skySampler, uv).rgb;
    }
    fragColor = vec4(color, skyAlpha);
}
