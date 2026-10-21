#version 120
uniform float uLight;
uniform sampler2D uTexture;
varying vec2 vUV;
uniform float uTime;
uniform float uEnchanted;
varying vec3 vNormal;
varying vec3 vColor;
varying vec3 vPosition;
varying float vEmissive;
void main() {
    vec3 base=texture2D(uTexture,vUV).rgb*vColor;
    vec3 n=normalize(vNormal);
    float diffuse=.46+.54*abs(dot(n,normalize(vec3(-.25,.75,.65))));
    float gloss=pow(max(0.0,dot(n,normalize(vec3(.1,.45,.9)))),28.0)*.24;
    vec3 color=base*diffuse*uLight+vec3(gloss*uLight);
    color=mix(color,base,vEmissive);
    float band=pow(.5+.5*sin(vPosition.y*38.0+vPosition.x*23.0-uTime*2.4),10.0);
    color+=uEnchanted*band*vec3(.19,.05,.27);
    gl_FragColor=vec4(color,1.0);
}
