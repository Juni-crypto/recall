import Foundation

/// Models Recall offers on iPhone. All are ungated on Hugging Face and pinned by SHA-256,
/// so a corrupted or swapped file is rejected. Same files as the Android app.
struct ModelSpec: Identifiable, Hashable {
    let id: String
    let name: String
    let repo: String
    let file: String
    let bytes: Int64
    let sha256: String
    let minRamGB: Double
    let note: String
    let license: String
    /// Qwen3 hybrid models think out loud unless told not to.
    let hybridThinking: Bool
    /// Big enough to sort messages. The 0.6B model copies examples instead, so the rules do it.
    var canReview: Bool { id != "qwen3-0.6b" }

    var url: URL { URL(string: "https://huggingface.co/\(repo)/resolve/main/\(file)?download=true")! }
}

enum ModelCatalog {
    static let all: [ModelSpec] = [
        ModelSpec(id: "qwen3-4b-2507", name: "Qwen3 4B Instruct", repo: "unsloth/Qwen3-4B-Instruct-2507-GGUF",
                  file: "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", bytes: 2_497_281_120,
                  sha256: "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597",
                  minRamGB: 8, note: "Best summaries and chat. iPhone 15 Pro and newer.", license: "Apache 2.0", hybridThinking: false),
        ModelSpec(id: "qwen3-1.7b", name: "Qwen3 1.7B", repo: "unsloth/Qwen3-1.7B-GGUF",
                  file: "Qwen3-1.7B-Q4_K_M.gguf", bytes: 1_107_409_472,
                  sha256: "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
                  minRamGB: 6, note: "For 6 GB iPhones. Shorter summaries.", license: "Apache 2.0", hybridThinking: true),
        ModelSpec(id: "qwen3-0.6b", name: "Qwen3 0.6B", repo: "unsloth/Qwen3-0.6B-GGUF",
                  file: "Qwen3-0.6B-Q4_K_M.gguf", bytes: 396_705_472,
                  sha256: "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a",
                  minRamGB: 4, note: "Tiny. Writes the summary and answers questions; the rules sort your messages.", license: "Apache 2.0", hybridThinking: true),
    ]

    static func byFile(_ file: String) -> ModelSpec? { all.first { $0.file == file } }

    static var ramGB: Double { Double(ProcessInfo.processInfo.physicalMemory) / 1_073_741_824 }

    /// The largest model this iPhone runs comfortably.
    static func recommended(ramGB: Double = ramGB) -> ModelSpec? { all.first { ramGB >= $0.minRamGB - 0.6 } }
}
