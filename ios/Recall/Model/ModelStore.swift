import CryptoKit
import Foundation
import Observation

/// Model files on the phone: download from Hugging Face (the only network request Recall
/// makes), import from Files, verify, pick the active one.
@MainActor @Observable
final class ModelStore: NSObject {
    static let shared = ModelStore()

    enum Download: Equatable {
        case idle
        case running(id: String, done: Int64, total: Int64)
        case verifying(id: String)
        case failed(String)
    }

    private(set) var installed: [URL] = []
    private(set) var download: Download = .idle
    /// Every request Recall made, for the network log.
    private(set) var networkLog: [String] = []

    var activeFile: String? {
        get { Shared.defaults.string(forKey: "activeModel") }
        set { Shared.defaults.set(newValue, forKey: "activeModel") }
    }

    var activeURL: URL? { activeFile.map { dir.appendingPathComponent($0) }.flatMap { FileManager.default.fileExists(atPath: $0.path) ? $0 : nil } }
    var activeSpec: ModelSpec? { activeFile.flatMap(ModelCatalog.byFile) }
    var activeName: String? { activeSpec?.name ?? activeFile?.replacingOccurrences(of: ".gguf", with: "") }
    /// Imported Qwen3 files other than the 2507 Instruct also need thinking turned off.
    var activeThinks: Bool { activeSpec?.hybridThinking ?? ((activeFile ?? "").lowercased().contains("qwen3") && !(activeFile ?? "").contains("2507")) }

    let dir: URL = {
        let d = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("models", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        var v = URLResourceValues(); v.isExcludedFromBackup = true
        var dd = d; try? dd.setResourceValues(v)
        return d
    }()

    private var task: URLSessionDownloadTask?
    private var observation: NSKeyValueObservation?
    private var pending: ModelSpec?

    override init() {
        super.init()
        refresh()
        networkLog = Shared.defaults.stringArray(forKey: "networkLog") ?? []
    }

    func refresh() {
        installed = ((try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "gguf" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
        if activeURL == nil { activeFile = installed.first?.lastPathComponent }
    }

    func isInstalled(_ spec: ModelSpec) -> Bool { installed.contains { $0.lastPathComponent == spec.file } }

    func start(_ spec: ModelSpec) {
        guard task == nil else { return }
        pending = spec
        download = .running(id: spec.id, done: 0, total: spec.bytes)
        log("GET \(spec.url.host() ?? "")/\(spec.repo)/\(spec.file)")
        let t = URLSession.shared.downloadTask(with: spec.url) { [weak self] tmp, response, error in
            // Move the file before this callback returns; the system deletes it afterwards.
            var kept: URL?
            if let tmp {
                let keep = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                if (try? FileManager.default.moveItem(at: tmp, to: keep)) != nil { kept = keep }
            }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            Task { @MainActor in self?.finished(spec, file: kept, status: status, error: error) }
        }
        observation = t.progress.observe(\.fractionCompleted) { [weak self] p, _ in
            let done = Int64(Double(spec.bytes) * p.fractionCompleted)
            Task { @MainActor in
                if case .running = self?.download { self?.download = .running(id: spec.id, done: done, total: spec.bytes) }
            }
        }
        task = t
        t.resume()
    }

    func cancel() {
        task?.cancel(); task = nil; observation = nil
        download = .idle
    }

    private func finished(_ spec: ModelSpec, file: URL?, status: Int, error: Error?) {
        task = nil; observation = nil
        guard let file, error == nil, (200..<300).contains(status) else {
            if (error as? URLError)?.code == .cancelled { download = .idle; return }
            download = .failed(error?.localizedDescription ?? "Download failed (HTTP \(status))")
            return
        }
        download = .verifying(id: spec.id)
        Task.detached(priority: .utility) {
            let sha = Self.sha256(of: file)
            await MainActor.run {
                guard sha == spec.sha256 else {
                    try? FileManager.default.removeItem(at: file)
                    self.download = .failed("The file didn't match its checksum and was deleted.")
                    return
                }
                let dest = self.dir.appendingPathComponent(spec.file)
                try? FileManager.default.removeItem(at: dest)
                try? FileManager.default.moveItem(at: file, to: dest)
                self.activeFile = spec.file
                self.download = .idle
                self.refresh()
            }
        }
    }

    /// A .gguf picked in Files. Known files are checked against their checksum.
    func importFile(_ url: URL) async throws {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard url.pathExtension.lowercased() == "gguf" else { throw ImportError(message: "Pick a .gguf model file.") }
        let dest = dir.appendingPathComponent(url.lastPathComponent)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.copyItem(at: url, to: dest)
        if let spec = ModelCatalog.byFile(url.lastPathComponent) {
            let sha = await Task.detached(priority: .utility) { Self.sha256(of: dest) }.value
            if sha != spec.sha256 {
                try? FileManager.default.removeItem(at: dest)
                throw ImportError(message: "This file doesn't match the published \(spec.name) and was deleted.")
            }
        }
        activeFile = dest.lastPathComponent
        refresh()
    }

    func delete(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
        if activeFile == url.lastPathComponent { activeFile = nil }
        refresh()
    }

    private func log(_ line: String) {
        let stamp = Date().formatted(date: .abbreviated, time: .shortened)
        networkLog.insert("\(stamp)  \(line)", at: 0)
        networkLog = Array(networkLog.prefix(100))
        Shared.defaults.set(networkLog, forKey: "networkLog")
    }

    nonisolated static func sha256(of url: URL) -> String {
        guard let h = try? FileHandle(forReadingFrom: url) else { return "" }
        defer { try? h.close() }
        var hasher = SHA256()
        while let chunk = try? h.read(upToCount: 8 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    struct ImportError: LocalizedError { let message: String; var errorDescription: String? { message } }
}
