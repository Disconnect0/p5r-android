#version 450
// Lightweight Mobile FXAA Console (5-tap with flat-pixel early exit) + optional zero-extra-tap CAS + Vivid / P5 Pop grade.
// Smooths 3D stair-stepping on Mali-G57 without smearing anime linework or UI text.
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

float luma(vec3 rgb) {
    return dot(rgb, vec3(0.299, 0.587, 0.114));
}

vec3 apply_color(vec3 c, float vib, float con, float style) {
    if (style > 0.5) {
        c = max(c - vec3(0.028), vec3(0.0)) / 0.972;
        vec3 s = c * c * (3.0 - 2.0 * c);
        c = mix(c, s, con);
        float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
        float shadowW = clamp(1.0 - l * 2.2, 0.0, 1.0);
        c += vec3(-0.015, -0.005, 0.028) * shadowW;
        float redDom = clamp((c.r - max(c.g, c.b)) * 2.2, 0.0, 1.0);
        float goldDom = clamp((min(c.r, c.g) - c.b) * 1.8, 0.0, 1.0);
        float mx = max(c.r, max(c.g, c.b));
        float mn = min(c.r, min(c.g, c.b));
        float sat = mx - mn;
        c = mix(vec3(l), c, 1.0 + vib * (1.0 - sat * 0.45));
        c.r += redDom * 0.09;
        c.rg += vec2(0.04, 0.025) * goldDom;
        return clamp(c, 0.0, 1.0);
    }
    if (vib > 0.001) {
        float mx = max(c.r, max(c.g, c.b));
        float mn = min(c.r, min(c.g, c.b));
        float sat = mx - mn;
        float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
        c = mix(vec3(l), c, 1.0 + vib * (1.0 - sat * 0.65));
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

    vec3 rgbM  = texture(src, tc).rgb;
    vec3 rgbNW = texture(src, clamp(tc + vec2(-0.5, -0.5) * invSz, lo, hi)).rgb;
    vec3 rgbNE = texture(src, clamp(tc + vec2( 0.5, -0.5) * invSz, lo, hi)).rgb;
    vec3 rgbSW = texture(src, clamp(tc + vec2(-0.5,  0.5) * invSz, lo, hi)).rgb;
    vec3 rgbSE = texture(src, clamp(tc + vec2( 0.5,  0.5) * invSz, lo, hi)).rgb;

    float lM  = luma(rgbM);
    float lNW = luma(rgbNW);
    float lNE = luma(rgbNE);
    float lSW = luma(rgbSW);
    float lSE = luma(rgbSE);

    float lMin = min(lM, min(min(lNW, lNE), min(lSW, lSE)));
    float lMax = max(lM, max(max(lNW, lNE), max(lSW, lSE)));
    float lRange = lMax - lMin;

    vec3 outC = rgbM;
    if (lRange >= max(0.045, lMax * 0.125)) {
        vec2 dir;
        dir.x = -((lNW + lNE) - (lSW + lSE));
        dir.y =  ((lNW + lSW) - (lNE + lSE));
        float dirReduce = max((lNW + lNE + lSW + lSE) * (0.25 * 0.125), 1.0 / 128.0);
        float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
        dir = clamp(dir * rcpDirMin, vec2(-4.0), vec2(4.0)) * invSz;

        vec3 rgbA = 0.5 * (
            texture(src, clamp(tc + dir * (1.0 / 3.0 - 0.5), lo, hi)).rgb +
            texture(src, clamp(tc + dir * (2.0 / 3.0 - 0.5), lo, hi)).rgb);
        vec3 rgbB = rgbA * 0.5 + 0.25 * (
            texture(src, clamp(tc + dir * -0.5, lo, hi)).rgb +
            texture(src, clamp(tc + dir *  0.5, lo, hi)).rgb);
        float lB = luma(rgbB);
        outC = (lB < lMin || lB > lMax) ? rgbA : rgbB;
    }

    // Optional zero-extra-tap CAS using the 4 diagonal neighbors already in registers
    if (p.post.x > 0.001) {
        vec3 mn = min(outC, min(min(rgbNW, rgbNE), min(rgbSW, rgbSE)));
        vec3 mx = max(outC, max(max(rgbNW, rgbNE), max(rgbSW, rgbSE)));
        vec3 amp = sqrt(clamp(min(mn, 2.0 - mx) / max(mx, vec3(1e-5)), 0.0, 1.0));
        float peak = -1.0 / mix(8.5, 5.5, clamp(p.post.x, 0.0, 1.0));
        vec3 w = amp * peak;
        outC = clamp(((rgbNW + rgbNE + rgbSW + rgbSE) * w + outC) / (1.0 + 4.0 * w), 0.0, 1.0);
    }

    if (p.post.y > 0.001 || p.post.z > 0.001 || p.post.w > 0.5)
        outC = apply_color(outC, p.post.y, p.post.z, p.post.w);
    color = vec4(outC, 1.0);
}
