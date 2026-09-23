import RecallCore
import SwiftUI

enum Tab: String { case today, waiting, home, money, you }

@MainActor @Observable
final class Navigation {
    static let shared = Navigation()
    var tab: Tab = .home
    var askPrefill: String?
    var showAsk = false
}

struct RootView: View {
    @Environment(AppModel.self) private var model
    @State private var nav = Navigation.shared

    var body: some View {
        Group {
            if model.onboarded {
                VStack(spacing: 0) {
                    ZStack {
                        switch nav.tab {
                        case .home: HomeView()
                        case .today: TodayView()
                        case .waiting: WaitingView()
                        case .money: MoneyView()
                        case .you: YouView()
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    TabBar(tab: $nav.tab, mood: mood, waiting: model.snapshot.waitingCount)
                }
                .sheet(isPresented: $nav.showAsk) { AskView(prefill: nav.askPrefill) }
            } else {
                OnboardingView()
            }
        }
        .background(RC.bg.ignoresSafeArea())
        .environment(nav)
        .tint(RC.accent)
    }

    private var mood: OrbMood {
        Brain.shared.state != .idle ? .thinking : OrbMood(model.snapshot.mood)
    }
}

private struct TabBar: View {
    @Binding var tab: Tab
    let mood: OrbMood
    let waiting: Int

    var body: some View {
        VStack(spacing: 0) {
            Rectangle().fill(RC.line).frame(height: 1)
            HStack(alignment: .center) {
                item(.today, "Today", "sun.horizon")
                item(.waiting, "Waiting", "hourglass", badge: waiting)
                Button { tab = .home } label: {
                    VStack(spacing: 0) {
                        RecallOrb(mood: mood, animate: false).frame(width: 52, height: 52).offset(y: -6)
                        Text("Recall").font(Type.mono(11)).foregroundStyle(tab == .home ? RC.accent : RC.muted).offset(y: -8)
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
                item(.money, "Money", "indianrupeesign")
                item(.you, "You", "person")
            }
            .frame(height: 62)
            .padding(.horizontal, 6)
        }
        .background(RC.surface2.ignoresSafeArea(edges: .bottom))
    }

    private func item(_ t: Tab, _ label: String, _ icon: String, badge: Int = 0) -> some View {
        Button { tab = t } label: {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 19))
                    .overlay(alignment: .topTrailing) {
                        if badge > 0 {
                            Text("\(badge)").font(Type.mono(10)).foregroundStyle(RC.bg)
                                .padding(.horizontal, 5).background(RC.wait, in: Capsule()).offset(x: 12, y: -6)
                        }
                    }
                Text(label).font(Type.mono(11))
            }
            .foregroundStyle(tab == t ? RC.accent : RC.muted)
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(badge > 0 ? "\(label), \(badge)" : label)
    }
}
