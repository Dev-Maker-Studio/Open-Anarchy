#version 460 core

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aColor;
layout(location = 2) in vec2 aTexCoord;
layout(location = 3) in vec3 aNormal;

out vec3 vColor;
out vec2 vTexCoord;
out vec3 vNormal;
out float vViewDist;
out vec3 vFragPos;
flat out int vMirrored;
flat out int vTextureLayer;
flat out int vTextureArray;

layout(std140) uniform CameraBlock {
    mat4 view;
    mat4 projection;
    mat4 viewProjection;
    vec3 cameraPos;
};

struct InstanceInfo {
    mat4 model;
    int textureLayer;
    int textureArray;
    int _pad1;
    int _pad2;
};

layout(std430, binding = 1) buffer InstanceData {
    InstanceInfo instances[];
};

layout(std430, binding = 2) readonly buffer VisibleData {
    uint visibleItems[];
};

void main() {
    InstanceInfo inst = instances[visibleItems[gl_BaseInstance + gl_InstanceID]];
    mat4 model = inst.model;

    vFragPos = vec3(model * vec4(aPosition, 1.0));
    mat3 normalMatrix = mat3(model);
    vNormal = normalMatrix * aNormal;
    vColor = aColor;
    vTexCoord = aTexCoord;
    vTextureLayer = inst.textureLayer;
    vTextureArray = inst.textureArray;
    vViewDist = distance(vFragPos, cameraPos);
    gl_Position = viewProjection * vec4(vFragPos, 1.0);

    float det = determinant(normalMatrix);
    vMirrored = (det < 0.0) ? 1 : 0;
}
