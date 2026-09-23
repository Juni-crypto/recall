import RecallCore
import SwiftUI

/// How messages reach Recall on iPhone: Shortcuts automations. Step by step, in the words
/// the Shortcuts app uses.
struct SetupGuideView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var testText = ""
    @State private var testResult: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("iPhone apps can't read other apps' notifications. Shortcuts can pass messages to Recall instead, automatically and without opening it. Everything stays on this iPhone.")
                    .font(Type.body(14)).foregroundStyle(RC.muted)

                guide(title: "Bank texts", icon: "message", steps: [
                    "Open **Shortcuts** → **Automation** → **+** (New Automation).",
                    "Choose **Message**. Under **Message Contains**, type **debited**. Leave Sender empty.",
                    "Pick **Run Immediately** and turn off **Notify When Run**. Tap **Next**.",
                    "Tap **New Blank Automation**, search for **Add to Recall** and add it.",
                    "Tap **Message** → choose **Shortcut Input**, then tap it again and pick **Content**.",
                    "Tap **From** → **Shortcut Input** → **Sender**. Leave Type as **Text message**. Tap **Done**.",
                    "Repeat for **credited**, so money coming in is caught too.",
                ])

                guide(title: "Texts from people", icon: "person.2", steps: [
                    "Make one more **Message** automation. Under **Sender**, pick the people whose asks you don't want to miss.",
                    "Same action: **Add to Recall** with Content and Sender from **Shortcut Input**.",
                ])

                guide(title: "Email", icon: "envelope", steps: [
                    "New automation → **Email**. Pick the **Account** (and a **Sender** or **Subject** if you like).",
                    "**Run Immediately**, then add **Add to Recall**.",
                    "Message → **Shortcut Input**. Subject → **Shortcut Input** → **Subject**. From → **Shortcut Input** → **Sender**. Type → **Email**.",
                    "This works with the Mail app. If you use the Gmail app, add the same account to Mail too (Settings → Apps → Mail → Mail Accounts).",
                ])

                guide(title: "WhatsApp chats", icon: "bubble.left.and.bubble.right", steps: [
                    "WhatsApp can't be automated. Open a chat → tap its name → **Export Chat** → **Without Media**.",
                    "In the share sheet, choose **Recall** (or **Save to Files**, then You → Import a WhatsApp chat).",
                ])

                PrimaryButton(title: "Open Shortcuts", systemImage: "arrow.up.forward.app") {
                    if let url = URL(string: "shortcuts://") { openURL(url) }
                }

                Card {
                    Label2(text: "Try it")
                    Text("Paste a bank text or a message someone sent you, to see what Recall makes of it.").font(Type.body(13)).foregroundStyle(RC.muted)
                    TextField("Rs.250 debited from A/c XX1234 to swiggy@icici…", text: $testText, axis: .vertical)
                        .font(Type.body(14)).lineLimit(3...6).padding(10)
                        .background(RC.bg, in: RoundedRectangle(cornerRadius: 12)).overlay(RoundedRectangle(cornerRadius: 12).stroke(RC.line))
                    HStack {
                        GhostButton(title: "Add as bank text") { add(.sms, sender: "AX-TESTBK") }
                        GhostButton(title: "Add as a message") { add(.sms, sender: "Test") }
                    }
                    if let testResult { Text(testResult).font(Type.mono(11)).foregroundStyle(RC.accent) }
                }
            }
            .padding(16)
        }
        .background(RC.bg)
        .navigationTitle("Set up Shortcuts")
    }

    private func add(_ source: Source, sender: String) {
        let before = model.store.openLoops().count
        let (s, e) = Clock.dayBounds()
        let spentBefore = model.store.moneyTotals(from: s, to: e)
        guard model.engine.intake(text: testText, sender: sender, source: source) else { testResult = "Nothing added (empty, or already there)."; return }
        let spent = model.store.moneyTotals(from: s, to: e)
        if spent != spentBefore { testResult = "Read as a payment. See Money." }
        else if model.store.openLoops().count > before { testResult = "Read as someone waiting on you. See Waiting." }
        else { testResult = "Stored. Nothing to act on in it." }
        testText = ""
        model.refreshStatus()
    }

    private func guide(title: String, icon: String, steps: [String]) -> some View {
        Card {
            HStack(spacing: 10) {
                Image(systemName: icon).foregroundStyle(RC.accent)
                Text(title).font(Type.body(16, weight: .semibold)).foregroundStyle(RC.text)
            }
            ForEach(Array(steps.enumerated()), id: \.offset) { i, s in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text("\(i + 1)").font(Type.mono(11)).foregroundStyle(RC.accentInk)
                        .frame(width: 20, height: 20).background(RC.accent, in: Circle())
                    Text(.init(s)).font(Type.body(14)).foregroundStyle(RC.muted)
                }
            }
        }
    }
}
