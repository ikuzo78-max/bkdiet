#version 300 es

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aUv;

uniform mat4 uMvp;

out vec2 vUv;

void main() {
    vUv = aUv;
    gl_Position = uMvp * vec4(aPosition, 0.0, 1.0);
}
