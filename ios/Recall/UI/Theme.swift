import SwiftUI

/// The Android app's night palette and type, so both apps look like one product.
enum RC {
    static let bg = Color(hex: 0x0E1116)
    static let surface = Color(hex: 0x161B27)
    static let surface2 = Color(hex: 0x131722)
    static let line = Color(hex: 0x242B3A)
    static let text = Color(hex: 0xE8EAF2)
    static let muted = Color(hex: 0x9AA3B8)
    static let dim = Color(hex: 0x6B7489)
    static let accent = Color(hex: 0xAEB9FF)
    static let accentInk = Color(hex: 0x141A3A)
    static let accentDeep = Color(hex: 0x3A4690)
    static let urgent = Color(hex: 0xFF6B6B)
    static let wait = Color(hex: 0xF2B84B)
    static let money = Color(hex: 0x4CC38A)
    static let invest = Color(hex: 0x8FA2FF)

    static let categoryColors: [String: Color] = [
        "Food": Color(hex: 0xF2B84B), "Groceries": Color(hex: 0x4CC38A), "Rent": Color(hex: 0xE9C46A),
        "Fuel": Color(hex: 0xFF6B6B), "Travel": Color(hex: 0x6EC6FF), "Shopping": Color(hex: 0xC792EA),
        "Bills": Color(hex: 0x82AAFF), "Subscriptions": Color(hex: 0xF78C6C), "Health": Color(hex: 0x89DDFF),
        "Savings": Color(hex: 0x8FA2FF), "Self transfer": Color(hex: 0x6B7489), "People": Color(hex: 0xFFCB6B),
        "Salary": Color(hex: 0x4CC38A), "Received": Color(hex: 0x4CC38A),
    ]
    static func color(for category: String) -> Color { categoryColors[category] ?? Color(hex: 0x9AA3B8) }
}

enum Type {
    static func display(_ size: CGFloat) -> Font { .custom("BricolageGrotesque-96ptExtraBold", size: size) }
    static func body(_ size: CGFloat = 16, weight: Font.Weight = .regular) -> Font { .custom("IBMPlexSans-Regular", size: size).weight(weight) }
    static func mono(_ size: CGFloat = 12) -> Font { .custom("IBMPlexMono-Regular", size: size) }
    static let label = mono(11)
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }
}

/// A rounded panel, the app's basic container.
struct Card<Content: View>: View {
    var padding: CGFloat = 16
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RC.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(RC.line, lineWidth: 1))
    }
}

/// Small uppercase label above a card's content, with an optional trailing value.
struct Label2: View {
    let text: String
    var trailing: String? = nil
    var body: some View {
        HStack {
            Text(text.uppercased()).font(Type.label).tracking(1.2).foregroundStyle(RC.dim)
            Spacer()
            if let trailing { Text(trailing).font(Type.label).foregroundStyle(RC.dim) }
        }
    }
}

struct Pill: View {
    let text: String
    var selected = false
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(text).font(Type.body(13, weight: .medium))
                .padding(.horizontal, 12).padding(.vertical, 7)
                .foregroundStyle(selected ? RC.accentInk : RC.text)
                .background(selected ? RC.accent : Color.clear, in: Capsule())
                .overlay(Capsule().stroke(selected ? Color.clear : RC.line, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

struct Tag: View {
    let text: String
    var color: Color = RC.accent
    var body: some View {
        Text(text).font(Type.mono(10)).foregroundStyle(color)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .overlay(Capsule().stroke(color.opacity(0.5), lineWidth: 1))
    }
}

struct ScreenTitle: View {
    let title: String
    var subtitle: String? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(Type.display(30)).foregroundStyle(RC.text)
            if let subtitle { Text(subtitle).font(Type.body(13)).foregroundStyle(RC.muted) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct PrimaryButton: View {
    let title: String
    var systemImage: String? = nil
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title).font(Type.body(15, weight: .semibold))
            }
            .padding(.horizontal, 18).frame(height: 46)
            .foregroundStyle(RC.accentInk)
            .background(RC.accent, in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

struct GhostButton: View {
    let title: String
    var systemImage: String? = nil
    var color: Color = RC.text
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title).font(Type.body(13, weight: .medium))
            }
            .padding(.horizontal, 12).frame(height: 34)
            .foregroundStyle(color)
            .overlay(Capsule().stroke(RC.line, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}
