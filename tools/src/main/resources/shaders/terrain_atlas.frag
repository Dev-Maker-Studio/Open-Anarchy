#version 330 core

in vec3 vColor;
in vec2 vTexCoord;
in vec3 vNormal;
in vec3 vFragPos;
in vec4 vAtlasTransform;

out vec4 FragColor;

uniform sampler2D atlasTexture;

void main() {
    // Emulate GL_REPEAT tiling within the atlas sub-region.
    // The original terrain UVs can exceed [0,1] for tiled textures;
    // fract() maps them back into a single texture period.
    //
    // IMPORTANT: Compute UV derivatives BEFORE fract() to avoid mip level discontinuity.
    // When fract() wraps from ~1.0 back to ~0.0, dFdx/dFdy see a massive UV jump,
    // causing the sampler to select a very coarse mip level that bleeds into adjacent
    // atlas textures. Using textureGrad() with pre-fract derivatives fixes this.
    vec2 atlasScale = vAtlasTransform.zw;
    vec2 dx = dFdx(vTexCoord) * atlasScale;
    vec2 dy = dFdy(vTexCoord) * atlasScale;

    vec2 tiledUV = fract(vTexCoord);
    vec2 atlasUV = vAtlasTransform.xy + tiledUV * atlasScale;
    vec4 texColor = textureGrad(atlasTexture, atlasUV, dx, dy);

    // Terrain vertex colors are already lit using the RS client's lighting algorithm
    // which modifies HSL lightness based on surface normals BEFORE RGB conversion.
    // We multiply texture by pre-lit vertex color and pass through directly.
    vec3 baseColor = texColor.rgb * vColor;
    float alpha = texColor.a;

    // Discard fully transparent fragments
    if (alpha < 0.01) {
        discard;
    }

    FragColor = vec4(baseColor, alpha);
}
