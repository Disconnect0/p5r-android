#version 450
// Fast 5-tap AMD FidelityFX CAS (Contrast Adaptive Sharpening) + optional Vivid / P5 Pop color grading.
// Works at any resolution ratio (including 1:1) with minimal Mali-G57 bandwidth and zero edge ringing.
layout(set = 0, binding = 0) uniform sampler2D src;
layout(push_constant) uniform P {
    vec4 rot;   // mat2 columns (x, y) (z, w)
    vec4 off;   // xy: rot offset; z: sharpness; w: target width / height (upright)
    vec4 box;   // x0 y0 w h
    vec4 crop;  // u0 v0 du dv
    vec4 post;  // x: CAS strength (0..1), y: vibrance, z: contrast, w: style (0 = vivid, 1 = p5 pop)
} p;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 color;

vec3 apply_color(vec3 c, float vib, float con, float style) {
    if (style > 0.5) {
        c = max(c - vec3(0.028), vec3(0.0)) / 0.972;
        vec3 s = c * c * (3.0 - 2.0 * c);
        c = mix(c, s, con);
        float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
        float shadowW = clamp(1.0 - luma * 2.2, 0.0, 1.0);
        c += vec3(-0.015, -0.005, 0.028) * shadowW;
        float redDom = clamp((c.r - max(c.g, c.b)) * 2.2, 0.0, 1.0);
        float goldDom = clamp((min(c.r, c.g) - c.b) * 1.8, 0.0, 1.0);
        float mx = max(c.r, max(c.g, c.b));
        float mn = min(c.r, min(c.g, c.b));
        float sat = mx - mn;
        c = mix(vec3(luma), c, 1.0 + vib * (1.0 - sat * 0.45));
        c.r += redDom * 0.09;
        c.rg += vec2(0.04, 0.025) * goldDom;
        return clamp(c, 0.0, 1.0);
    }
    if (vib > 0.001) {
        float mx = max(c.r, max(c.g, c.b));
        float mn = min(c.r, min(c.g, c.b));
        float sat = mx - mn;
        float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
        c = mix(vec3(luma), c, 1.0 + vib * (1.0 - sat * 0.65));
    }
    if (con > 0.001) {
        vec3 s = c * c * (3.0 - 2.0 * c);
        c = mix(c, s, con);
    }
    return clamp(c, 0.0, 1.0);
}

void main() {
    vec2 up = mat2(p.rot.xy, p.rot.zw) * uv + p.off.xy;
    vec2 b = (up - p.box.xy) / p.box.zw;
    if (any(lessThan(b, vec2(0.0))) || any(greaterThan(b, vec2(1.0)))) {
        color = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec2 invSz = 1.0 / vec2(textureSize(src, 0));
    vec2 lo = p.crop.xy + 0.5 * invSz;
    vec2 hi = p.crop.xy + p.crop.zw - 0.5 * invSz;
    vec2 tc = clamp(p.crop.xy + b * p.crop.zw, lo, hi);

    vec3 cC = texture(src, tc).rgb;
    vec3 cN = texture(src, clamp(tc + vec2(0.0, -invSz.y), lo, hi)).rgb;
    vec3 cS = texture(src, clamp(tc + vec2(0.0,  invSz.y), lo, hi)).rgb;
    vec3 cW = texture(src, clamp(tc + vec2(-invSz.x, 0.0), lo, hi)).rgb;
    vec3 cE = texture(src, clamp(tc + vec2( invSz.x, 0.0), lo, hi)).rgb;

    vec3 mn = min(cC, min(min(cN, cS), min(cW, cE)));
    vec3 mx = max(cC, max(max(cN, cS), max(cW, cE)));
    vec3 amp = sqrt(clamp(min(mn, 2.0 - mx) / max(mx, vec3(1e-5)), 0.0, 1.0));
    float peak = -1.0 / mix(8.0, 5.0, clamp(p.post.x, 0.0, 1.0));
    vec3 w = amp * peak;
    vec3 outC = clamp(((cN + cS + cW + cE) * w + cC) / (1.0 + 4.0 * w), 0.0, 1.0);

    if (p.post.y > 0.001 || p.post.z > 0.001 || p.post.w > 0.5)
        outC = apply_color(outC, p.post.y, p.post.z, p.post.w);
    color = vec4(outC, 1.0);
}
