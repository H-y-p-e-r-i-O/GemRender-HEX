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

    // Direct illumination from celestial source (Sun / Moon)
    float dot0 = max(0.0, dot(L0, N));
    // Fill illumination from secondary source / ground bounce
    float dot1 = max(0.0, dot(L1, N));

    float skyLight = clamp(_gr_lightCoord.y, 0.0, 1.0);
    float blockLight = clamp(_gr_lightCoord.x, 0.0, 1.0);

    // Hemispherical ambient (sky from above, ground bounce from below)
    float hemi = mix(0.55, 1.0, clamp(N.y * 0.5 + 0.5, 0.0, 1.0));

    // When under the sky (skyLight -> 1.0), directional sun contrast is fully active.
    // In caves/indoors (skyLight -> 0.0), lighting smoothly becomes soft omnidirectional.
    float directCelestial = dot0 * 0.85 + dot1 * 0.15;
    float directionalFactor = mix(0.65, directCelestial, skyLight);
    float diffuse = mix(0.35, 1.0, directionalFactor) * mix(0.70, hemi, skyLight);

    vec3 lightmap = texture(_gr_lightmap, _gr_lightCoord).rgb;
    vec3 lit = colour.rgb * diffuse * lightmap;

    // Specular highlight (Blinn-Phong metallic glint)
    // In view space, camera eye is looking along -Z, so direction towards eye is +Z
    vec3 V = vec3(0.0, 0.0, 1.0);
    vec3 H = normalize(L0 + V);
    float NdotH = max(0.0, dot(N, H));
    float sunSpec = pow(NdotH, 32.0) * skyLight;
    float torchSpec = pow(max(0.0, dot(N, V)), 16.0) * blockLight * 0.20;

    vec3 specColor = vec3(1.0, 0.96, 0.90) * (sunSpec * 0.50 + torchSpec);
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
