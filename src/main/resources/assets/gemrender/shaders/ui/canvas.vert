#version 330 core

layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aColor;
layout(location = 3) in vec4 aParam;
layout(location = 4) in ivec2 aKind;

uniform mat4 uMatrix;

out vec2 vUv;
out vec4 vColor;
out vec4 vParam;
flat out ivec2 vKind;

void main() {
    gl_Position = uMatrix * vec4(aPos, 0.0, 1.0);
    vUv = aUv;
    vColor = aColor;
    vParam = aParam;
    vKind = aKind;
}
