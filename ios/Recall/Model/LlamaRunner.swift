import Foundation
import llama

/// The on-device model, through llama.cpp's C API (Metal on the phone's GPU).
/// One model is loaded at a time; generation can be cancelled from any thread.
actor LlamaRunner {
    static let shared = LlamaRunner()

    struct Failure: Error, CustomStringConvertible { let description: String }

    private var model: OpaquePointer?
    private var ctx: OpaquePointer?
    private var vocab: OpaquePointer?
    private(set) var loadedPath: String?
    private let abort = AbortFlag()
    private let nCtx: UInt32 = 4096

    init() {
        llama_backend_init()
    }

    var isLoaded: Bool { ctx != nil }

    func load(path: String) throws {
        if loadedPath == path, ctx != nil { return }
        unload()
        var mp = llama_model_default_params()
        #if targetEnvironment(simulator)
        mp.n_gpu_layers = 0
        #else
        mp.n_gpu_layers = 99
        #endif
        guard let m = llama_model_load_from_file(path, mp) else { throw Failure(description: "Couldn't load the model file") }
        var cp = llama_context_default_params()
        cp.n_ctx = nCtx
        cp.n_batch = 512
        let threads = Int32(max(2, min(6, ProcessInfo.processInfo.activeProcessorCount - 2)))
        cp.n_threads = threads
        cp.n_threads_batch = threads
        guard let c = llama_init_from_model(m, cp) else {
            llama_model_free(m)
            throw Failure(description: "Not enough memory for this model")
        }
        llama_set_abort_callback(c, { data in
            guard let data else { return false }
            return Unmanaged<AbortFlag>.fromOpaque(data).takeUnretainedValue().isSet
        }, Unmanaged.passUnretained(abort).toOpaque())
        model = m
        ctx = c
        vocab = llama_model_get_vocab(m)
        loadedPath = path
    }

    func unload() {
        if let ctx { llama_free(ctx) }
        if let model { llama_model_free(model) }
        ctx = nil; model = nil; vocab = nil; loadedPath = nil
    }

    /// Stops a running generation. Safe to call from outside the actor.
    nonisolated func cancel() { abort.set(true) }

    /// Generates a reply. `onText` gets the visible text so far (thinking blocks removed).
    func generate(system: String, user: String, maxTokens: Int, temperature: Float, noThink: Bool,
                  onText: (@Sendable (String) -> Void)? = nil) throws -> String {
        guard let ctx, let model, let vocab else { throw Failure(description: "No model loaded") }
        abort.set(false)

        let prompt = format(model: model, system: system, user: noThink ? user + "\n/no_think" : user)
        var tokens = tokenize(vocab, prompt)
        let budget = Int(nCtx) - maxTokens - 8
        guard budget > 0 else { return "" }
        if tokens.count > budget {
            // Keep the start (instructions) and the end (the question); drop the middle.
            let head = budget / 3
            tokens = Array(tokens.prefix(head)) + Array(tokens.suffix(budget - head))
        }

        llama_memory_clear(llama_get_memory(ctx), true)
        let nBatch = Int(llama_n_batch(ctx))
        var i = 0
        while i < tokens.count {
            let n = min(nBatch, tokens.count - i)
            let rc = tokens.withUnsafeMutableBufferPointer { buf in
                llama_decode(ctx, llama_batch_get_one(buf.baseAddress! + i, Int32(n)))
            }
            if abort.isSet { return "" }
            guard rc == 0 else { throw Failure(description: "The model couldn't read the prompt (\(rc))") }
            i += n
        }

        let chain = llama_sampler_chain_init(llama_sampler_chain_default_params())!
        defer { llama_sampler_free(chain) }
        if temperature <= 0.01 {
            llama_sampler_chain_add(chain, llama_sampler_init_greedy())
        } else {
            llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9, 1))
            llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature))
            llama_sampler_chain_add(chain, llama_sampler_init_dist(UInt32.random(in: 1...UInt32.max)))
        }

        var bytes: [UInt8] = []
        var produced = 0
        var piece = [CChar](repeating: 0, count: 256)
        while produced < maxTokens, !abort.isSet {
            var tok = llama_sampler_sample(chain, ctx, -1)
            if llama_vocab_is_eog(vocab, tok) { break }
            let n = llama_token_to_piece(vocab, tok, &piece, Int32(piece.count), 0, false)
            if n > 0 { bytes.append(contentsOf: piece[0..<Int(n)].map { UInt8(bitPattern: $0) }) }
            produced += 1
            if let onText, let s = String(validatingUTF8Prefix: bytes) { onText(Self.visible(s)) }
            let rc = llama_decode(ctx, llama_batch_get_one(&tok, 1))
            if rc != 0 { break }
        }
        return Self.visible(String(decoding: bytes, as: UTF8.self)).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Helpers

    /// Qwen3 can think out loud first; only the answer is shown.
    static func visible(_ s: String) -> String {
        s.replacingOccurrences(of: #"<think>[\s\S]*?(</think>|$)"#, with: "", options: .regularExpression)
    }

    private func format(model: OpaquePointer, system: String, user: String) -> String {
        let tmpl = llama_model_chat_template(model, nil)
        let roles = [strdup("system"), strdup("user")]
        let contents = [strdup(system), strdup(user)]
        defer { (roles + contents).forEach { free($0) } }
        var msgs = [
            llama_chat_message(role: roles[0], content: contents[0]),
            llama_chat_message(role: roles[1], content: contents[1]),
        ]
        var buf = [CChar](repeating: 0, count: (system.utf8.count + user.utf8.count) * 2 + 512)
        var n = llama_chat_apply_template(tmpl, &msgs, msgs.count, true, &buf, Int32(buf.count))
        if n > buf.count {
            buf = [CChar](repeating: 0, count: Int(n) + 1)
            n = llama_chat_apply_template(tmpl, &msgs, msgs.count, true, &buf, Int32(buf.count))
        }
        guard n > 0 else { return system + "\n\n" + user + "\n\n" }
        return String(decoding: buf[0..<Int(n)].map { UInt8(bitPattern: $0) }, as: UTF8.self)
    }

    private func tokenize(_ vocab: OpaquePointer, _ text: String) -> [llama_token] {
        let utf8Count = text.utf8.count
        var tokens = [llama_token](repeating: 0, count: utf8Count + 16)
        var n = llama_tokenize(vocab, text, Int32(utf8Count), &tokens, Int32(tokens.count), true, true)
        if n < 0 {
            tokens = [llama_token](repeating: 0, count: Int(-n))
            n = llama_tokenize(vocab, text, Int32(utf8Count), &tokens, Int32(tokens.count), true, true)
        }
        return Array(tokens.prefix(max(0, Int(n))))
    }
}

/// Read by llama.cpp's abort callback on its own thread.
final class AbortFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    var isSet: Bool { lock.lock(); defer { lock.unlock() }; return value }
    func set(_ v: Bool) { lock.lock(); value = v; lock.unlock() }
}

private extension String {
    /// Decodes the longest valid UTF-8 prefix, so a half-received emoji doesn't show as garbage.
    init?(validatingUTF8Prefix bytes: [UInt8]) {
        var end = bytes.count
        while end > 0 && end > bytes.count - 4 {
            if let s = String(bytes: bytes[0..<end], encoding: .utf8) { self = s; return }
            end -= 1
        }
        guard let s = String(bytes: bytes[0..<end], encoding: .utf8) else { return nil }
        self = s
    }
}
