import RecallCore
import SwiftUI
import UniformTypeIdentifiers

struct YouView: View {
    @Environment(AppModel.self) private var model
    @State private var importing = false
    @State private var message: String?
    @State private var confirmWipe = false
    @State private var names = ""
    @State private var digestTime = Date()

    var body: some View {
        let _ = model.version
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    ScreenTitle(title: "You", subtitle: "Everything here stays on this iPhone")
                    Card {
                        row("Set up Shortcuts", "How your texts and emails reach Recall", "bolt") { SetupGuideView() }
                        Divider().overlay(RC.line)
                        row("Model", ModelStore.shared.activeName ?? "None yet · Recall works without one", "cpu") { ModelsView() }
                        Divider().overlay(RC.line)
                        row("What Recall knows", "\(model.store.facts().count) things it learned", "brain") { LearnedView() }
                        Divider().overlay(RC.line)
                        Button { importing = true } label: {
                            rowLabel("Import a WhatsApp chat", "Export chat → Without media → pick the .zip", "square.and.arrow.down")
                        }
                        .buttonStyle(.plain)
                    }
                    Card {
                        Label2(text: "Digest")
                        DatePicker("Nightly digest", selection: $digestTime, displayedComponents: .hourAndMinute)
                            .font(Type.body(14)).foregroundStyle(RC.text)
                            .onChange(of: digestTime) { _, t in
                                let c = Calendar.current.dateComponents([.hour, .minute], from: t)
                                DigestScheduler.hour = c.hour ?? 21; DigestScheduler.minute = c.minute ?? 30
                                model.refreshStatus()
                            }
                        Label2(text: "Your names")
                        TextField("Names you go by, comma separated", text: $names)
                            .font(Type.body(14)).padding(10)
                            .background(RC.bg, in: RoundedRectangle(cornerRadius: 12)).overlay(RoundedRectangle(cornerRadius: 12).stroke(RC.line))
                            .onSubmit { model.engine.userNames = names.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) } }
                        Text("So \"@you\" in a group chat counts as asked of you.").font(Type.body(12)).foregroundStyle(RC.dim)
                    }
                    Card {
                        Label2(text: "Privacy")
                        NavigationLink { NetworkLogView() } label: { rowLabel("Network activity", "Every request Recall has made", "network") }.buttonStyle(.plain)
                        Divider().overlay(RC.line)
                        Button { confirmWipe = true } label: { rowLabel("Delete all data", "Messages, payments and what it learned", "trash", color: RC.urgent) }.buttonStyle(.plain)
                    }
                    Text("Recall \(Bundle.main.shortVersion) · open source · github.com/Juni-crypto/recall").font(Type.mono(10)).foregroundStyle(RC.dim)
                }
                .padding(16)
            }
            .background(RC.bg)
            .fileImporter(isPresented: $importing, allowedContentTypes: [.zip, .plainText, .text]) { result in
                guard case let .success(url) = result else { return }
                do {
                    let r = try ChatImport.importFile(url, engine: model.engine)
                    message = "Imported \(r.imported) messages from \(r.chat)."
                } catch { message = error.localizedDescription }
            }
            .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK", role: .cancel) {} }
            .confirmationDialog("Delete everything Recall stored?", isPresented: $confirmWipe, titleVisibility: .visible) {
                Button("Delete all data", role: .destructive) { model.store.wipe(); model.refreshStatus() }
            } message: { Text("Messages, payments, what it learned and chats. Model files stay.") }
            .onAppear {
                names = model.engine.userNames.joined(separator: ", ")
                digestTime = Calendar.current.date(bySettingHour: DigestScheduler.hour, minute: DigestScheduler.minute, second: 0, of: Date()) ?? Date()
            }
        }
    }

    private func row<D: View>(_ title: String, _ sub: String, _ icon: String, @ViewBuilder destination: () -> D) -> some View {
        NavigationLink(destination: destination) { rowLabel(title, sub, icon) }.buttonStyle(.plain)
    }

    private func rowLabel(_ title: String, _ sub: String, _ icon: String, color: Color = RC.text) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).frame(width: 24).foregroundStyle(RC.accent)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(Type.body(15, weight: .medium)).foregroundStyle(color)
                Text(sub).font(Type.body(12)).foregroundStyle(RC.muted).lineLimit(1)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.system(size: 12)).foregroundStyle(RC.dim)
        }
        .contentShape(Rectangle())
    }
}

struct LearnedView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let _ = model.version
        let facts = model.store.facts()
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Learned from your own messages and taps. Delete anything that's wrong; Recall forgets it and won't write it again.")
                    .font(Type.body(13)).foregroundStyle(RC.muted)
                if facts.isEmpty {
                    Card { Text("Nothing yet. Recall writes what it notices once a day.").font(Type.body(14)).foregroundStyle(RC.muted) }
                }
                ForEach(Dictionary(grouping: facts, by: \.topic).sorted { $0.key < $1.key }, id: \.key) { topic, items in
                    Card {
                        Label2(text: topic)
                        ForEach(items) { f in
                            HStack(alignment: .top) {
                                Text(f.text).font(Type.body(14)).foregroundStyle(RC.text)
                                Spacer()
                                Button { model.store.deleteFact(f.id) } label: { Image(systemName: "xmark").foregroundStyle(RC.dim) }
                                    .accessibilityLabel("Forget this")
                            }
                        }
                    }
                }
                let owner = model.engine.ownerNames
                if !owner.isEmpty {
                    Card {
                        Label2(text: "Your name at the bank")
                        Text(owner.joined(separator: ", ")).font(Type.body(14)).foregroundStyle(RC.text)
                        Text("Money between accounts in this name counts as a self transfer, not spending.").font(Type.body(12)).foregroundStyle(RC.dim)
                    }
                }
            }
            .padding(16)
        }
        .background(RC.bg)
        .navigationTitle("What Recall knows")
    }
}

struct NetworkLogView: View {
    var body: some View {
        let log = ModelStore.shared.networkLog
        List {
            Section {
                if log.isEmpty { Text("No requests yet.").foregroundStyle(RC.muted) }
                ForEach(log, id: \.self) { Text($0).font(Type.mono(11)).foregroundStyle(RC.text) }
            } footer: {
                Text("Recall only connects to download a model file from Hugging Face. Your messages never leave this iPhone.")
            }
            .listRowBackground(RC.surface)
        }
        .scrollContentBackground(.hidden)
        .background(RC.bg)
        .navigationTitle("Network activity")
    }
}

extension Bundle {
    var shortVersion: String { (infoDictionary?["CFBundleShortVersionString"] as? String) ?? "" }
}
