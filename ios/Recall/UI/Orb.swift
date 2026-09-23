import SwiftUI

enum OrbMood: String {
    case calm, needs, urgent, off, thinking

    init(_ s: String) { self = OrbMood(rawValue: s) ?? .calm }

    var colors: (Color, Color, Color, Float) {
        switch self {
        case .calm: (.rgb(0.07, 0.20, 0.48), .rgb(0.25, 0.76, 0.85), .rgb(0.80, 0.93, 1.00), 0.15)
        case .needs: (.rgb(0.14, 0.10, 0.46), .rgb(0.49, 0.42, 1.00), .rgb(0.95, 0.72, 0.29), 0.42)
        case .urgent: (.rgb(0.40, 0.06, 0.18), .rgb(1.00, 0.37, 0.38), .rgb(1.00, 0.77, 0.42), 0.85)
        case .thinking: (.rgb(0.06, 0.16, 0.42), .rgb(0.56, 0.64, 1.00), .rgb(1.00, 1.00, 1.00), 1.00)
        case .off: (.rgb(0.12, 0.13, 0.17), .rgb(0.36, 0.39, 0.47), .rgb(0.62, 0.65, 0.72), 0.05)
        }
    }
}

/// The orb. Animated at up to 30 fps when `animate` is on; a still frame otherwise (tab bar, widget).
struct RecallOrb: View {
    var mood: OrbMood
    var animate = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !animate || reduceMotion)) { ctx in
            let t = animate && !reduceMotion ? Float(ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 10_000)) : 12
            let (a, b, c, e) = mood.colors
            Rectangle()
                .visualEffect { content, proxy in
                    content.colorEffect(ShaderLibrary.recallOrb(
                        .float4(0, 0, Float(proxy.size.width), Float(proxy.size.height)),
                        .float(t), .color(a), .color(b), .color(c), .float(e),
                    ))
                }
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }
}

private extension Color {
    static func rgb(_ r: Double, _ g: Double, _ b: Double) -> Color { Color(.sRGB, red: r, green: g, blue: b) }
}
