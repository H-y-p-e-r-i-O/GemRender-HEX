uniform sampler2D _gr_atlas;
uniform sampler2D _gr_lightmap;
uniform sampler2D _gr_overlayTex;

uniform vec3 _gr_light0;
uniform vec3 _gr_light1;

uniform float _gr_alphaCutoff;
uniform int _gr_blended;

in vec2 _gr_texCoord;
in vec2 _gr_lightCoord;
in vec4 _gr_tint;
in vec3 _gr_shadeNormal;
in vec2 _gr_overlayCoord;
in vec3 _gr_paintCoord;
flat in int _gr_paintLayer;
flat in int _gr_paintReference;

// Coverage lives in the emissive band's alpha; paintable models are banded (3 bands).
const float GR_EMISSIVE_BAND = 2.0 / 3.0;

layout(location = 0) out vec4 _gr_fragColor;
layout(location = 1) out vec4 _gr_fragColor1;
layout(location = 2) out vec4 _gr_fragColor2;
layout(location = 3) out vec4 _gr_fragColor3;

void main() {
    vec4 colour = texture(_gr_atlas, _gr_texCoord) * _gr_tint;
    if (_gr_paintLayer >= 0) {
        vec4 paint = gemrender_paint(_gr_paintLayer, _gr_paintCoord,
                texture(_gr_atlas, _gr_texCoord + vec2(0.0, GR_EMISSIVE_BAND)).a);
        vec3 reference = gemrender_unpackPaint(_gr_paintReference).rgb;
        vec3 repainted = min(vec3(1.0), colour.rgb * paint.rgb / max(reference, vec3(1.0 / 255.0)));
        colour.rgb = mix(colour.rgb, repainted, paint.a);
    }

    if (colour.a <= _gr_alphaCutoff) {
        discard;
    }

    vec3 N = length(_gr_shadeNormal) > 0.001 ? normalize(_gr_shadeNormal) : vec3(0.0, 1.0, 0.0);
    vec3 L0 = normalize(_gr_light0);
    vec3 L1 = normalize(_gr_light1);

    // Directional lighting with enhanced contrast
    float dot0 = max(0.0, dot(L0, N));
    float dot1 = max(0.0, dot(L1, N));

    // Hemispherical ambient: top faces receive cool sky ambient, bottom faces are in shadow
    float hemi = mix(0.55, 1.0, clamp(N.y * 0.5 + 0.5, 0.0, 1.0));

    // Main diffuse term with deep shadows and readable 3D shapes
    float diffuse = mix(0.40, 1.0, dot0 * 0.75 + dot1 * 0.25) * hemi;

    vec3 lightmap = texture(_gr_lightmap, _gr_lightCoord).rgb;
    vec3 lit = colour.rgb * diffuse * lightmap;

    // Specular highlight (Blinn-Phong metallic glint)
    // In view space, camera is looking down -Z, so view direction towards eye is +Z
    vec3 V = vec3(0.0, 0.0, 1.0);
    vec3 H = normalize(L0 + V);
    float NdotH = max(0.0, dot(N, H));
    float spec = pow(NdotH, 32.0);

    // Specular only shows up where there is sky or block light, scaled nicely
    float lightIntensity = max(_gr_lightCoord.y, _gr_lightCoord.x * 0.75);
    vec3 specColor = vec3(1.0, 0.96, 0.90) * (spec * 0.35 * lightIntensity);
    lit += specColor * lightmap;

    vec4 overlay = texture(_gr_overlayTex, _gr_overlayCoord);
    lit = mix(overlay.rgb, lit, overlay.a);

    float alpha = _gr_blended != 0 ? colour.a : 1.0;
    vec3 transMult = _gr_blended != 0 ? mix(vec3(0.666), colour.rgb * (1.0 - pow(colour.a, 4.0)), colour.a) : vec3(0.0);
    _gr_fragColor = vec4(lit, alpha);
    _gr_fragColor1 = vec4(1.0 - transMult, 1.0);
    _gr_fragColor2 = vec4(0.0, 254.0 / 255.0, _gr_lightCoord.y, 1.0);
    _gr_fragColor3 = vec4(N * 0.5 + 0.5, 0.0);
}
