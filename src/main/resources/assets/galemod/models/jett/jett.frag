#version 120
uniform sampler2D uTexture;
uniform float uLight;
uniform float uHurt;
varying vec2 vUV;
varying float vShade;
void main() {
    vec4 color = texture2D(uTexture, vUV);
    if (color.a < 0.04) discard;
    vec3 shaded = color.rgb * vShade * uLight;
    shaded = mix(shaded, shaded * vec3(1.0, 0.38, 0.38), uHurt);
    gl_FragColor = vec4(shaded, color.a);
}
