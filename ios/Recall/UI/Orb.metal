#include <metal_stdlib>
#include <SwiftUI/SwiftUI_Metal.h>
using namespace metal;

// The Recall orb: liquid noise inside a lit sphere, with a soft glow. Same shader as the
// Android app (AGSL) and the website (WebGL).

static float h(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }

static float n(float2 p) {
    float2 i = floor(p), f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(h(i), h(i + float2(1, 0)), u.x), mix(h(i + float2(0, 1)), h(i + float2(1, 1)), u.x), u.y);
}

static float fbm(float2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) { v += a * n(p); p = p * 2.03 + float2(1.7, 9.2); a *= 0.5; }
    return v;
}

[[ stitchable ]] half4 recallOrb(float2 pos, half4 color, float4 bounds, float time, half4 ha, half4 hb, half4 hc, float e) {
    float3 A = float3(ha.rgb), B = float3(hb.rgb), C = float3(hc.rgb);
    float2 size = bounds.zw;
    float2 uv = (pos - 0.5 * size) / min(size.x, size.y);
    uv.y = -uv.y;
    float t = time * (0.10 + 0.42 * e);
    float R = 0.33 * (1.0 + (0.012 + 0.02 * e) * sin(time * (0.8 + 2.2 * e)));
    float r = length(uv);
    float inside = smoothstep(R, R - 0.004, r);
    float2 p = uv / R;
    float z = sqrt(max(0.0, 1.0 - dot(p, p)));
    float2 q = p * 1.5 / (0.55 + z * 0.75);
    float2 w = float2(fbm(q + float2(t, -t * 0.7)), fbm(q + float2(-t * 0.8, t * 0.5) + 5.2));
    float m = fbm(q + 2.4 * w + float2(t * 0.3, -t * 0.2));
    float3 col = mix(A, B, smoothstep(0.28, 0.78, m));
    col = mix(col, C, smoothstep(0.5, 0.95, w.y * m * 1.7) * 0.85);
    float fres = pow(1.0 - z, 2.2);
    col *= 0.5 + 0.65 * z;
    col += fres * mix(B, float3(1.0), 0.35) * 0.85;
    float3 L = normalize(float3(-0.45, 0.55, 0.7));
    col += pow(max(0.0, dot(normalize(float3(p, z)), L)), 28.0) * 0.45;
    float d = max(r - R, 0.0);
    float g = exp(-d * 11.0) * smoothstep(0.49, 0.36, r) * (1.0 - inside) * (0.5 + 0.3 * e + 0.08 * sin(time * (1.1 + 2.4 * e)));
    float3 gc = mix(A, B, 0.65);
    float alpha = clamp(inside + g * 0.55, 0.0, 1.0);
    float3 rgb = col * inside + gc * g * 0.55;
    return half4(half3(rgb), half(alpha));
}
