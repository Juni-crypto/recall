import SwiftUI
import UniformTypeIdentifiers

struct ModelsView: View {
    @State private var models = ModelStore.shared
    @State private var importing = false
    @State private var error: String?
    @State private var testOutput: String?
    @State private var testing = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("The model runs on this iPhone's GPU. It's only downloaded once, from Hugging Face, and checked against a pinned checksum.")
                    .font(Type.body(13)).foregroundStyle(RC.muted)
                Text(String(format: "This iPhone: %.0f GB RAM", ModelCatalog.ramGB)).font(Type.mono(11)).foregroundStyle(RC.dim)

                ForEach(ModelCatalog.all) { spec in
                    Card {
                        HStack {
                            Text(spec.name).font(Type.body(16, weight: .semibold)).foregroundStyle(RC.text)
                            if spec == ModelCatalog.recommended() { Tag(text: "for this iPhone") }
                            Spacer()
                            Text(ByteCountFormatter.string(fromByteCount: spec.bytes, countStyle: .file)).font(Type.mono(11)).foregroundStyle(RC.dim)
                        }
                        Text("\(spec.note) \(spec.license).").font(Type.body(13)).foregroundStyle(RC.muted)
                        status(spec)
                    }
                }

                Card {
                    Label2(text: "Other ways")
                    Text("Already have a .gguf file? Import it from Files.").font(Type.body(13)).foregroundStyle(RC.muted)
                    GhostButton(title: "Import a .gguf file", systemImage: "folder") { importing = true }
                }

                if models.activeURL != nil {
                    Card {
                        Label2(text: "Test the model")
                        Text("Runs \(models.activeName ?? "it") once and shows how fast it is.").font(Type.body(13)).foregroundStyle(RC.muted)
                        GhostButton(title: testing ? "Running…" : "Try it") { test() }
                        if let testOutput { Text(testOutput).font(Type.body(13)).foregroundStyle(RC.text) }
                    }
                }
                if let error { Text(error).font(Type.body(13)).foregroundStyle(RC.urgent) }
            }
            .padding(16)
        }
        .background(RC.bg)
        .navigationTitle("Models")
        .fileImporter(isPresented: $importing, allowedContentTypes: [UTType(filenameExtension: "gguf") ?? .data, .data]) { result in
            guard case let .success(url) = result else { return }
            Task {
                do { try await models.importFile(url) } catch { self.error = error.localizedDescription }
            }
        }
    }

    @ViewBuilder
    private func status(_ spec: ModelSpec) -> some View {
        switch models.download {
        case let .running(id, done, total) where id == spec.id:
            ProgressView(value: Double(done), total: Double(max(total, 1))).tint(RC.accent)
            HStack {
                Text("\(ByteCountFormatter.string(fromByteCount: done, countStyle: .file)) of \(ByteCountFormatter.string(fromByteCount: total, countStyle: .file)). Keep Recall open.")
                    .font(Type.mono(11)).foregroundStyle(RC.dim)
                Spacer()
                GhostButton(title: "Cancel", color: RC.muted) { models.cancel() }
            }
        case let .verifying(id) where id == spec.id:
            Text("Checking the file…").font(Type.mono(11)).foregroundStyle(RC.dim)
        default:
            if models.isInstalled(spec) {
                HStack {
                    if models.activeFile == spec.file { Tag(text: "in use", color: RC.money) }
                    else { GhostButton(title: "Use this") { models.activeFile = spec.file } }
                    Spacer()
                    GhostButton(title: "Delete", color: RC.urgent) { models.delete(models.dir.appendingPathComponent(spec.file)) }
                }
            } else {
                HStack {
                    PrimaryButton(title: "Download", systemImage: "arrow.down") { models.start(spec) }
                    if case let .failed(msg) = models.download { Text(msg).font(Type.body(12)).foregroundStyle(RC.urgent) }
                }
            }
        }
    }

    private func test() {
        guard !testing else { return }
        testing = true
        testOutput = nil
        Task {
            let t0 = Date()
            let out = await Brain.shared.generate(system: "You are Recall, a private assistant on the user's phone.",
                                                  user: "In one short sentence, say hello and what you can help with.",
                                                  maxTokens: 60, temperature: 0.4, load: .interactive, onText: nil)
            let secs = Date().timeIntervalSince(t0)
            testOutput = out.map { "\($0)\n\(String(format: "%.1f s", secs))" } ?? (Brain.shared.blockedReason ?? "The model couldn't load. Try a smaller one.")
            testing = false
        }
    }
}
