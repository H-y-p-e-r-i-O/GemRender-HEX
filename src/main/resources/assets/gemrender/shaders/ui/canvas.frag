#version 330 core

const int SOLID = 0;
const int TEXTURE = 1;
const int INTENSITY = 2;
const int LINE = 3;
const int ARC = 4;
const int ROUND_RECT = 5;
const float TAU = 6.28318530718;

uniform sampler2D uTex0;
uniform sampler2D uTex1;
uniform sampler2D uTex2;
uniform sampler2D uTex3;
uniform sampler2D uTex4;
uniform sampler2D uTex5;
uniform sampler2D uTex6;
uniform sampler2D uTex7;

in vec2 vUv;
in vec4 vColor;
in vec4 vParam;
flat in ivec2 vKind;

out vec4 fragColor;

vec4 sampleSlot(int slot, vec2 uv, vec2 dx, vec2 dy) {
    if (slot == 0) return textureGrad(uTex0, uv, dx, dy);
    if (slot == 1) return textureGrad(uTex1, uv, dx, dy);
    if (slot == 2) return textureGrad(uTex2, uv, dx, dy);
    if (slot == 3) return textureGrad(uTex3, uv, dx, dy);
    if (slot == 4) return textureGrad(uTex4, uv, dx, dy);
    if (slot == 5) return textureGrad(uTex5, uv, dx, dy);
    if (slot == 6) return textureGrad(uTex6, uv, dx, dy);
    return textureGrad(uTex7, uv, dx, dy);
}

ivec2 sizeSlot(int slot) {
    if (slot == 0) return textureSize(uTex0, 0);
    if (slot == 1) return textureSize(uTex1, 0);
    if (slot == 2) return textureSize(uTex2, 0);
    if (slot == 3) return textureSize(uTex3, 0);
    if (slot == 4) return textureSize(uTex4, 0);
    if (slot == 5) return textureSize(uTex5, 0);
    if (slot == 6) return textureSize(uTex6, 0);
    return textureSize(uTex7, 0);
}

vec4 fetchSlot(int slot, ivec2 p) {
    if (slot == 0) return texelFetch(uTex0, p, 0);
    if (slot == 1) return texelFetch(uTex1, p, 0);
    if (slot == 2) return texelFetch(uTex2, p, 0);
    if (slot == 3) return texelFetch(uTex3, p, 0);
    if (slot == 4) return texelFetch(uTex4, p, 0);
    if (slot == 5) return texelFetch(uTex5, p, 0);
    if (slot == 6) return texelFetch(uTex6, p, 0);
    return texelFetch(uTex7, p, 0);
}

// Sharp bilinear: texel edges blend over one screen pixel => == nearest at integer scale, even columns at fractional.
vec4 samplePixelated(int slot, vec2 uv) {
    ivec2 size = sizeSlot(slot);
    vec2 t = uv * vec2(size) - 0.5;
    vec2 base = floor(t);
    vec2 f = clamp((t - base - 0.5) / max(fwidth(t), vec2(1e-4)) + 0.5, 0.0, 1.0);
    ivec2 p0 = clamp(ivec2(base), ivec2(0), size - 1);
    ivec2 p1 = clamp(ivec2(base) + 1, ivec2(0), size - 1);
    vec4 top = mix(fetchSlot(slot, p0), fetchSlot(slot, ivec2(p1.x, p0.y)), f.x);
    vec4 bottom = mix(fetchSlot(slot, ivec2(p0.x, p1.y)), fetchSlot(slot, p1), f.x);
    return mix(top, bottom, f.y);
}

float cover(float d, float pixel) {
    return clamp(0.5 - d / max(pixel, 1e-6), 0.0, 1.0);
}

void main() {
    vec2 dx = dFdx(vUv);
    vec2 dy = dFdy(vUv);
    float pixel = 0.5 * (length(dx) + length(dy));
    int mode = vKind.x;

    vec4 color = vColor;
    if (mode == TEXTURE) {
        color *= vParam.x > 0.0 ? samplePixelated(vKind.y, vUv) : sampleSlot(vKind.y, vUv, dx, dy);
    } else if (mode == INTENSITY) {
        color *= (vParam.x > 0.0 ? samplePixelated(vKind.y, vUv) : sampleSlot(vKind.y, vUv, dx, dy)).rrrr;
    } else if (mode == LINE) {
        vec2 q = abs(vUv) - vParam.xy;
        color.a *= cover(max(q.x, q.y), pixel);
    } else if (mode == ARC) {
        float r = length(vUv);
        float d = max(r - vParam.x, vParam.y - r);
        if (vParam.w < TAU) {
            float a = mod(atan(vUv.y, vUv.x) - vParam.z, TAU);
            float edge = a <= vParam.w ? -min(a, vParam.w - a) : min(a - vParam.w, TAU - a);
            d = max(d, edge * max(r, pixel));
        }
        color.a *= cover(d, pixel);
    } else if (mode == ROUND_RECT) {
        vec2 q = abs(vUv) - vParam.xy + vParam.z;
        float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - vParam.z;
        if (vParam.w > 0.0) {
            d = abs(d + 0.5 * vParam.w) - 0.5 * vParam.w;
        }
        color.a *= cover(d, pixel);
    }
    if (color.a <= 0.0) {
        discard;
    }
    fragColor = color;
}
