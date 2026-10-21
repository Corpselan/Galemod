#version 120
attribute vec3 aPosition;
attribute vec3 aNormal;
attribute vec3 aColor;
attribute float aEmissive;
varying vec3 vNormal;
varying vec3 vColor;
varying vec3 vPosition;
varying float vEmissive;
void main() {
    vNormal=normalize(gl_NormalMatrix*aNormal);
    vColor=aColor;vEmissive=aEmissive;vPosition=aPosition;
    gl_Position=gl_ModelViewProjectionMatrix*vec4(aPosition,1.0);
}
