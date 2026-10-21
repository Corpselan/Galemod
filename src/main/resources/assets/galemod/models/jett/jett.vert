#version 120
attribute vec3 aPosition;
attribute vec3 aNormal;
attribute vec2 aUV;
attribute vec4 aJoints;
attribute vec4 aWeights;
attribute vec3 aGrip;
uniform mat4 uBones[15];
uniform float uGrip;
varying vec2 vUV;
varying float vShade;
void main() {
    mat4 skin = uBones[int(aJoints.x)] * aWeights.x
              + uBones[int(aJoints.y)] * aWeights.y
              + uBones[int(aJoints.z)] * aWeights.z
              + uBones[int(aJoints.w)] * aWeights.w;
    vec4 position = skin * vec4(aPosition + aGrip * uGrip, 1.0);
    vec3 normal = normalize(mat3(skin) * aNormal);
    vShade = 0.72 + 0.28 * max(dot(normal, normalize(vec3(-0.3, 0.8, -0.5))), 0.0);
    vUV = aUV;
    gl_Position = gl_ModelViewProjectionMatrix * position;
}
