package app.recall.ui.orb

import android.graphics.RuntimeShader
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** How the orb looks is how the day is going. */
enum class OrbMood(val a: Color, val b: Color, val c: Color, val energy: Float, val description: String) {
    Calm(Color(0.07f, 0.20f, 0.48f), Color(0.25f, 0.76f, 0.85f), Color(0.80f, 0.93f, 1.00f), 0.15f, "Nothing needs you"),
    Needs(Color(0.14f, 0.10f, 0.46f), Color(0.49f, 0.42f, 1.00f), Color(0.95f, 0.72f, 0.29f), 0.42f, "People are waiting on you"),
    Urgent(Color(0.40f, 0.06f, 0.18f), Color(1.00f, 0.37f, 0.38f), Color(1.00f, 0.77f, 0.42f), 0.85f, "Something urgent needs you"),
    Thinking(Color(0.06f, 0.16f, 0.42f), Color(0.56f, 0.64f, 1.00f), Color(1f, 1f, 1f), 1.0f, "Recall is thinking"),
    Off(Color(0.13f, 0.14f, 0.17f), Color(0.42f, 0.44f, 0.50f), Color(0.70f, 0.70f, 0.72f), 0.08f, "Recall isn't capturing"),
}

// Same shader as the design page: fbm "liquid" inside a lit sphere, with a soft glow.
private const val ORB_AGSL = """
uniform float2 uRes;
uniform float uTime;
uniform float3 uA;
uniform float3 uB;
uniform float3 uC;
uniform float uE;

float h(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float n(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(h(i), h(i + float2(1.0, 0.0)), u.x), mix(h(i + float2(0.0, 1.0)), h(i + float2(1.0, 1.0)), u.x), u.y);
}
float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) { v += a * n(p); p = p * 2.03 + float2(1.7, 9.2); a *= 0.5; }
    return v;
}
half4 main(float2 fc) {
    float2 uv = (fc - 0.5 * uRes) / min(uRes.x, uRes.y);
    uv.y = -uv.y;
    float t = uTime * (0.10 + 0.42 * uE);
    float R = 0.33 * (1.0 + (0.012 + 0.02 * uE) * sin(uTime * (0.8 + 2.2 * uE)));
    float r = length(uv);
    float inside = 1.0 - smoothstep(R - 0.004, R, r);
    float2 p = uv / R;
    float z = sqrt(max(0.0, 1.0 - dot(p, p)));
    float2 q = p * 1.5 / (0.55 + z * 0.75);
    float2 w = float2(fbm(q + float2(t, -t * 0.7)), fbm(q + float2(-t * 0.8, t * 0.5) + 5.2));
    float m = fbm(q + 2.4 * w + float2(t * 0.3, -t * 0.2));
    float3 col = mix(uA, uB, smoothstep(0.28, 0.78, m));
    col = mix(col, uC, smoothstep(0.5, 0.95, w.y * m * 1.7) * 0.85);
    float fres = pow(1.0 - z, 2.2);
    col *= 0.5 + 0.65 * z;
    col += fres * mix(uB, float3(1.0), 0.35) * 0.85;
    float3 L = normalize(float3(-0.45, 0.55, 0.7));
    col += pow(max(0.0, dot(normalize(float3(p, z + 0.0001)), L)), 28.0) * 0.45;
    float d = max(r - R, 0.0);
    float g = exp(-d * 11.0) * (1.0 - smoothstep(0.36, 0.49, r)) * (1.0 - inside)
        * (0.5 + 0.3 * uE + 0.08 * sin(uTime * (1.1 + 2.4 * uE)));
    float3 gc = mix(uA, uB, 0.65);
    float al = clamp(inside + g * 0.55, 0.0, 1.0);
    return half4(half3(col * inside + gc * g * 0.55), half(al));
}
"""

@Composable
private fun rememberOrbTime(animate: Boolean): State<Float> {
    val context = LocalContext.current
    val still = remember(animate) {
        !animate || Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    return produceState(12f) {
        if (still) return@produceState
        var start = -1L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (start < 0) start = now
                // 30 fps is plenty for a slow liquid; half the redraws of 60.
                val t = ((now - start) / 1_000_000_000.0 % 3600.0)
                value = 12f + (Math.floor(t * 30) / 30).toFloat()
            }
        }
    }
}

@Composable
fun RecallOrb(mood: OrbMood, modifier: Modifier = Modifier, animate: Boolean = true, onClick: (() -> Unit)? = null) {
    val a by animateColorAsState(mood.a, tween(1200), label = "orbA")
    val b by animateColorAsState(mood.b, tween(1200), label = "orbB")
    val c by animateColorAsState(mood.c, tween(1200), label = "orbC")
    val e by animateFloatAsState(mood.energy, tween(1200), label = "orbE")
    val time = rememberOrbTime(animate)

    val clickable = if (onClick != null) {
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    } else {
        Modifier
    }
    val m = modifier.then(clickable).semantics { contentDescription = "Recall. ${mood.description}" }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ShaderOrb(m, time, a, b, c, e)
    } else {
        GradientOrb(m, time, a, b, e)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderOrb(modifier: Modifier, time: State<Float>, a: Color, b: Color, c: Color, e: Float) {
    val shader = remember { RuntimeShader(ORB_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(modifier) {
        shader.setFloatUniform("uRes", size.width, size.height)
        shader.setFloatUniform("uTime", time.value)
        shader.setFloatUniform("uA", a.red, a.green, a.blue)
        shader.setFloatUniform("uB", b.red, b.green, b.blue)
        shader.setFloatUniform("uC", c.red, c.green, c.blue)
        shader.setFloatUniform("uE", e)
        drawRect(brush)
    }
}

/** Android 12 and below: no runtime shaders, so a layered gradient that still breathes. */
@Composable
private fun GradientOrb(modifier: Modifier, time: State<Float>, a: Color, b: Color, e: Float) {
    Canvas(modifier) {
        val t = time.value
        val base = min(size.width, size.height) * 0.33f
        val r = base * (1f + (0.012f + 0.02f * e) * sin(t * (0.8f + 2.2f * e)))
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(
            Brush.radialGradient(listOf(b.copy(alpha = 0.35f), Color.Transparent), center, r * 1.45f),
            radius = r * 1.45f, center = center,
        )
        val hl = Offset(center.x - r * 0.3f + cos(t * 0.3f) * r * 0.08f, center.y - r * 0.32f + sin(t * 0.25f) * r * 0.08f)
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), b, a, a.copy(alpha = 1f)), hl, r * 1.6f),
            radius = r, center = center,
        )
    }
}
