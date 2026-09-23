import Compression
import Foundation
import RecallCore

/// Imports a WhatsApp chat export (WhatsApp → chat → Export chat → Without media).
/// On iPhone it arrives as a .zip holding _chat.txt; a plain .txt works too.
enum ChatImport {
    struct Result { let chat: String; let imported: Int }
    struct Failure: LocalizedError { let errorDescription: String? }

    @MainActor
    static func importFile(_ url: URL, engine: RecallEngine) throws -> Result {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        let text: String
        if url.pathExtension.lowercased() == "zip" || data.starts(with: [0x50, 0x4B, 0x03, 0x04]) {
            guard let entry = try ZipReader(data).entries().first(where: { $0.name.lowercased().hasSuffix(".txt") }),
                  let s = String(data: entry.data, encoding: .utf8) else { throw Failure(errorDescription: "That zip has no chat in it.") }
            text = s
        } else {
            guard let s = String(data: data, encoding: .utf8) else { throw Failure(errorDescription: "Couldn't read that file as text.") }
            text = s
        }
        let chat = WhatsAppImport.chatName(fileName: url.lastPathComponent)
        let parsed = WhatsAppImport.parse(chat, text, selfNames: engine.userNames)
        guard !parsed.isEmpty else { throw Failure(errorDescription: "Couldn't find any messages in that file.") }

        let recent = Clock.now - 2 * Clock.day
        var imported = 0
        try engine.store.db.transaction {
            for p in parsed {
                let c = Captured(hash: RecallEngine.sha1("wa|\(chat)|\(p.sender)|\(p.at)|\(p.text)"), source: .whatsapp,
                                 convKey: "whatsapp|\(chat)", convTitle: chat, sender: p.mine ? "You" : p.sender,
                                 text: p.text, isSelf: p.mine, isGroup: p.isGroup, at: p.at)
                // Old history is context; only the last two days can still be waiting on you.
                if engine.handle(c, trackRequests: p.at >= recent) { imported += 1 }
            }
        }
        return Result(chat: chat, imported: imported)
    }
}

/// Reads stored and deflated entries from a .zip (enough for WhatsApp exports).
struct ZipReader {
    struct Entry { let name: String; let data: Data }
    struct Failure: Error {}

    private let d: Data
    init(_ data: Data) { d = data }

    private func u16(_ o: Int) -> Int { Int(d[d.startIndex + o]) | Int(d[d.startIndex + o + 1]) << 8 }
    private func u32(_ o: Int) -> Int { u16(o) | u16(o + 2) << 16 }

    func entries() throws -> [Entry] {
        // End of central directory: signature 0x06054b50, within the last 64 KB.
        guard d.count >= 22 else { throw Failure() }
        var eocd = -1
        for o in stride(from: d.count - 22, through: max(0, d.count - 65_557), by: -1) where u32(o) == 0x0605_4B50 { eocd = o; break }
        guard eocd >= 0 else { throw Failure() }
        let count = u16(eocd + 10)
        var p = u32(eocd + 16)
        var out: [Entry] = []
        for _ in 0..<count {
            guard p + 46 <= d.count, u32(p) == 0x0201_4B50 else { throw Failure() }
            let method = u16(p + 10)
            let csize = u32(p + 20), usize = u32(p + 24)
            let nlen = u16(p + 28), elen = u16(p + 30), clen = u16(p + 32)
            let local = u32(p + 42)
            let name = String(decoding: d[(d.startIndex + p + 46)..<(d.startIndex + p + 46 + nlen)], as: UTF8.self)
            p += 46 + nlen + elen + clen
            guard local + 30 <= d.count, u32(local) == 0x0403_4B50 else { continue }
            let start = local + 30 + u16(local + 26) + u16(local + 28)
            guard start + csize <= d.count else { continue }
            let raw = d[(d.startIndex + start)..<(d.startIndex + start + csize)]
            switch method {
            case 0: out.append(Entry(name: name, data: Data(raw)))
            case 8: if let inflated = Self.inflate(Data(raw), size: usize) { out.append(Entry(name: name, data: inflated)) }
            default: continue
            }
        }
        return out
    }

    private static func inflate(_ src: Data, size: Int) -> Data? {
        guard size > 0 else { return Data() }
        var dst = Data(count: size)
        let n = dst.withUnsafeMutableBytes { dp in
            src.withUnsafeBytes { sp in
                compression_decode_buffer(dp.bindMemory(to: UInt8.self).baseAddress!, size,
                                          sp.bindMemory(to: UInt8.self).baseAddress!, src.count, nil, COMPRESSION_ZLIB)
            }
        }
        return n == size ? dst : nil
    }
}
