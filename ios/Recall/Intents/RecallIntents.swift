import AppIntents
import RecallCore

/// iOS doesn't let apps read other apps' notifications. Instead, Shortcuts automations
/// ("When I get a message from…", "When I get an email…") hand each message to this action.
enum MessageKind: String, AppEnum {
    case sms, mail, whatsapp, note

    static var typeDisplayRepresentation: TypeDisplayRepresentation { "Message type" }
    static var caseDisplayRepresentations: [MessageKind: DisplayRepresentation] {
        [.sms: "Text message", .mail: "Email", .whatsapp: "WhatsApp", .note: "Note"]
    }

    var source: Source { Source(rawValue: rawValue) ?? .note }
}

struct AddMessageIntent: AppIntent {
    static var title: LocalizedStringResource { "Add to Recall" }
    static var description: IntentDescription {
        IntentDescription("Hands a message to Recall. Use it in an automation for texts from your bank, or for emails. The message stays on this iPhone.")
    }
    static var openAppWhenRun: Bool { false }

    @Parameter(title: "Message", inputOptions: String.IntentInputOptions(multiline: true))
    var text: String

    @Parameter(title: "From")
    var sender: String?

    @Parameter(title: "Subject")
    var subject: String?

    @Parameter(title: "Type", default: .sms)
    var kind: MessageKind

    static var parameterSummary: some ParameterSummary {
        Summary("Add \(\.$text) from \(\.$sender) to Recall") {
            \.$kind
            \.$subject
        }
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        let model = AppModel.shared
        model.engine.intake(text: text, sender: sender, subject: subject, source: kind.source)
        model.refreshStatus()
        return .result()
    }
}

struct WhatNeedsMeIntent: AppIntent {
    static var title: LocalizedStringResource { "What needs me" }
    static var description: IntentDescription { IntentDescription("Says who's waiting on you and what you spent today.") }
    static var openAppWhenRun: Bool { false }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let s = StatusSnapshot.make(store: AppModel.shared.store)
        var lines = [s.headline + "."]
        for item in s.items.prefix(3) { lines.append("\(item.person): \(item.text)") }
        if s.spentTodayPaise > 0 { lines.append("You spent \(Fmt.rupees(s.spentTodayPaise)) today.") }
        return .result(dialog: IntentDialog(stringLiteral: lines.joined(separator: "\n")))
    }
}

struct RecallShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: WhatNeedsMeIntent(), phrases: ["What needs me in \(.applicationName)", "Ask \(.applicationName) what's waiting"],
                    shortTitle: "What needs me", systemImageName: "hourglass")
        AppShortcut(intent: AddMessageIntent(), phrases: ["Add to \(.applicationName)"],
                    shortTitle: "Add to Recall", systemImageName: "tray.and.arrow.down")
    }
}
